package org.nm.gdrive_backup.domain.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Supplier;

import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveChainException;
import org.nm.gdrive_backup.domain.model.ArchiveManifest;
import org.nm.gdrive_backup.domain.model.ArchiveManifest.ManifestFile;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.ArchiveReader;
import org.nm.gdrive_backup.domain.model.AvailableDrive;
import org.nm.gdrive_backup.domain.model.DeletionCommit;
import org.nm.gdrive_backup.domain.model.DeletionPlan;
import org.nm.gdrive_backup.domain.model.DeletionResult;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.DriveScopeType;
import org.nm.gdrive_backup.domain.model.FileCapture;
import org.nm.gdrive_backup.domain.model.LostContent;
import org.nm.gdrive_backup.domain.model.ObsoleteArchive;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.port.in.ArchiveDeletionUseCase;
import org.nm.gdrive_backup.domain.port.out.ArchiveDeletionCommitPort;
import org.nm.gdrive_backup.domain.port.out.ArchivePort;
import org.nm.gdrive_backup.domain.port.out.ArchiveReaderPort;
import org.nm.gdrive_backup.domain.port.out.ArchiveStoragePort;
import org.nm.gdrive_backup.domain.port.out.FileCapturePort;
import org.nm.gdrive_backup.domain.port.out.FileEventPort;
import org.nm.gdrive_backup.domain.port.out.FileMetadataPort;

/**
 * Deletes the archives a merge made obsolete, but only after the merged full has been re-read completely and the
 * admin has seen exactly what goes. The archives are the only copy of the data, so {@link #prepare} changes
 * nothing, {@link #execute} refuses an unverified or out-of-date plan, and the database is updated before any file
 * is removed (a failure part-way leaves files behind, never rows pointing at missing files). The chains an earlier
 * from-scratch full left behind go the same way, after every archive of the current chain has been re-read.
 */
public class ArchiveDeletionService implements ArchiveDeletionUseCase {

	private final ArchivePort archivePort;
	private final ArchiveReaderPort archiveReaderPort;
	private final ArchiveStoragePort archiveStoragePort;
	private final FileCapturePort fileCapturePort;
	private final FileEventPort fileEventPort;
	private final FileMetadataPort fileMetadataPort;
	private final ArchiveDeletionCommitPort deletionCommitPort;
	private final BackupActivity backupActivity;
	private final BackupProgressTracker progressTracker;
	private final BackupCancellation cancellation;

	public ArchiveDeletionService(ArchivePort archivePort, ArchiveReaderPort archiveReaderPort,
			ArchiveStoragePort archiveStoragePort, FileCapturePort fileCapturePort, FileEventPort fileEventPort,
			FileMetadataPort fileMetadataPort, ArchiveDeletionCommitPort deletionCommitPort,
			BackupActivity backupActivity, BackupProgressTracker progressTracker, BackupCancellation cancellation) {
		this.archivePort = archivePort;
		this.archiveReaderPort = archiveReaderPort;
		this.archiveStoragePort = archiveStoragePort;
		this.fileCapturePort = fileCapturePort;
		this.fileEventPort = fileEventPort;
		this.fileMetadataPort = fileMetadataPort;
		this.deletionCommitPort = deletionCommitPort;
		this.backupActivity = backupActivity;
		this.progressTracker = progressTracker;
		this.cancellation = cancellation;
	}

	@Override
	public Optional<DeletionPlan> prepare(DriveScope scope, String scopeDisplayNameOrNull) {
		return preparing(scope, scopeDisplayNameOrNull, () -> prepareVerifiedPlan(scope));
	}

	@Override
	public DeletionResult execute(DriveScope scope, DeletionPlan plan) {
		return backupActivity.duringExclusiveOperation(() -> carryOut(scope, plan));
	}

	@Override
	public Optional<DeletionPlan> prepareEarlierChains(DriveScope scope, String scopeDisplayNameOrNull) {
		return preparing(scope, scopeDisplayNameOrNull, () -> prepareEarlierChainsPlan(scope));
	}

	@Override
	public DeletionResult executeEarlierChains(DriveScope scope, DeletionPlan plan) {
		return backupActivity.duringExclusiveOperation(() -> carryOutEarlierChains(scope, plan));
	}

	private Optional<DeletionPlan> preparing(DriveScope scope, String scopeDisplayNameOrNull,
			Supplier<Optional<DeletionPlan>> work) {
		return backupActivity.duringExclusiveOperation(() -> {
			AvailableDrive drive = new AvailableDrive(scope.key(),
					scopeDisplayNameOrNull != null ? scopeDisplayNameOrNull : scope.key(),
					scope.type() == DriveScopeType.SHARED_DRIVE);
			cancellation.begin();
			progressTracker.jobStarted(List.of(drive));
			progressTracker.driveStarted(drive);
			try {
				Optional<DeletionPlan> plan = work.get();
				progressTracker.driveCompleted();
				return plan;
			} finally {
				progressTracker.jobFinished();
			}
		});
	}

	private Optional<DeletionPlan> prepareVerifiedPlan(DriveScope scope) {
		Context context = context(scope);
		progressTracker.enumerating();
		List<String> problems = new ArrayList<>();
		ArchiveManifest manifest = null;
		try (ArchiveReader reader = archiveReaderPort.open(context.merged().archivePath())) {
			manifest = reader.manifest();
			checkManifest(scope, context.merged(), manifest, problems);
			List<ManifestFile> withContent = manifest.files().stream().filter(file -> file.entry() != null).toList();
			progressTracker.enumerated(withContent.size());
			for (ManifestFile file : withContent) {
				if (cancellation.isImmediateStopRequested()) {
					return Optional.empty();
				}
				progressTracker.itemProcessed(file.name());
				verifyEntry(reader, file, problems);
			}
		} catch (IOException exception) {
			problems.add("The merged archive " + context.merged().archivePath() + " cannot be read: "
					+ exception.getMessage());
		}

		IndexChanges changes = manifest == null ? IndexChanges.EMPTY : indexChanges(context.obsolete(), manifest, problems);
		return Optional.of(new DeletionPlan(scope, context.merged().id(), context.tip().id(),
				describe(context.obsolete()), changes.lostContent(), changes.repoint().size(), changes.remove().size(),
				changes.events(), problems));
	}

	/** Re-reads every archive of the current chain: once the earlier chains are gone it is the drive's only backup. */
	private Optional<DeletionPlan> prepareEarlierChainsPlan(DriveScope scope) {
		EarlierChains context = earlierChains(scope);
		progressTracker.enumerating();
		List<String> problems = new ArrayList<>();
		List<ArchiveReader> readers = new ArrayList<>();
		try {
			int entries = 0;
			for (Archive archive : context.chain()) {
				try {
					ArchiveReader reader = archiveReaderPort.open(archive.archivePath());
					readers.add(reader);
					checkManifest(scope, archive, reader.manifest(), problems);
					entries += (int) reader.manifest().files().stream().filter(file -> file.entry() != null).count();
				} catch (IOException exception) {
					problems.add("Archive " + archive.sequenceNumber() + " of the current chain (" + archive.archivePath()
							+ ") cannot be read: " + exception.getMessage());
				}
			}
			progressTracker.enumerated(entries);
			for (ArchiveReader reader : readers) {
				for (ManifestFile file : reader.manifest().files()) {
					if (file.entry() == null) {
						continue;
					}
					if (cancellation.isImmediateStopRequested()) {
						return Optional.empty();
					}
					progressTracker.itemProcessed(file.name());
					verifyEntry(reader, file, problems);
				}
			}
		} finally {
			readers.forEach(ArchiveReader::close);
		}
		IndexChanges changes = earlierChainChanges(context.earlier());
		return Optional.of(new DeletionPlan(scope, context.root().id(), context.tip().id(), describe(context.earlier()),
				changes.lostContent(), 0, changes.remove().size(), changes.events(), problems));
	}

	private List<ObsoleteArchive> describe(List<Archive> archives) {
		return archives.stream()
				.sorted(Comparator.comparingInt(Archive::sequenceNumber))
				.map(archive -> {
					OptionalLong size = archiveStoragePort.sizeOf(archive.archivePath());
					return new ObsoleteArchive(archive.id(), archive.sequenceNumber(), archive.archivePath(),
							size.isPresent() ? size.getAsLong() : null);
				})
				.toList();
	}

	private DeletionResult carryOut(DriveScope scope, DeletionPlan plan) {
		if (!plan.verified()) {
			throw new IllegalStateException("The deletion has verification problems and cannot be carried out: "
					+ String.join("; ", plan.verificationProblems()));
		}
		Context context = context(scope);
		Set<Long> currentObsolete = new HashSet<>();
		context.obsolete().forEach(archive -> currentObsolete.add(archive.id()));
		Set<Long> plannedObsolete = new HashSet<>();
		plan.obsolete().forEach(archive -> plannedObsolete.add(archive.id()));
		if (context.merged().id() != plan.mergedArchiveId() || context.tip().id() != plan.tipArchiveId()
				|| !currentObsolete.equals(plannedObsolete)) {
			throw staleError();
		}

		ArchiveManifest manifest;
		try (ArchiveReader reader = archiveReaderPort.open(context.merged().archivePath())) {
			manifest = reader.manifest();
		} catch (IOException exception) {
			throw new ArchiveChainException("The merged archive " + context.merged().archivePath()
					+ " cannot be read: " + exception.getMessage(), exception);
		}
		List<String> problems = new ArrayList<>();
		IndexChanges changes = indexChanges(context.obsolete(), manifest, problems);
		if (!problems.isEmpty()) {
			throw new IllegalStateException("The deletion cannot be carried out: " + String.join("; ", problems));
		}
		if (changes.repoint().size() != plan.capturesToRepoint() || changes.remove().size() != plan.capturesToRemove()
				|| changes.events() != plan.eventsToRepoint()) {
			throw staleError();
		}

		return commitAndDelete(context.merged(), context.obsolete(), changes);
	}

	private DeletionResult carryOutEarlierChains(DriveScope scope, DeletionPlan plan) {
		if (!plan.verified()) {
			throw new IllegalStateException("The deletion has verification problems and cannot be carried out: "
					+ String.join("; ", plan.verificationProblems()));
		}
		EarlierChains context = earlierChains(scope);
		Set<Long> currentEarlier = new HashSet<>();
		context.earlier().forEach(archive -> currentEarlier.add(archive.id()));
		Set<Long> plannedEarlier = new HashSet<>();
		plan.obsolete().forEach(archive -> plannedEarlier.add(archive.id()));
		IndexChanges changes = earlierChainChanges(context.earlier());
		if (context.root().id() != plan.mergedArchiveId() || context.tip().id() != plan.tipArchiveId()
				|| !currentEarlier.equals(plannedEarlier) || changes.remove().size() != plan.capturesToRemove()
				|| changes.events() != plan.eventsToRepoint()) {
			throw staleError();
		}
		return commitAndDelete(context.root(), context.earlier(), changes);
	}

	/** Database first, then the files, newest first: a failure part-way never leaves a row pointing at a missing file. */
	private DeletionResult commitAndDelete(Archive kept, List<Archive> removed, IndexChanges changes) {
		List<Archive> newestFirst = removed.stream()
				.sorted(Comparator.comparingInt(Archive::sequenceNumber).reversed())
				.toList();
		deletionCommitPort.apply(new DeletionCommit(kept.id(),
				newestFirst.stream().map(Archive::id).toList(), changes.repoint(), changes.remove()));

		int deleted = 0;
		long freed = 0;
		List<String> failures = new ArrayList<>();
		for (Archive archive : newestFirst) {
			OptionalLong size = archiveStoragePort.sizeOf(archive.archivePath());
			try {
				archiveStoragePort.delete(archive.archivePath());
				if (size.isPresent()) {
					deleted++;
					freed += size.getAsLong();
				}
			} catch (IOException | RuntimeException exception) {
				failures.add(archive.archivePath() + " (" + exception.getMessage() + ")");
			}
		}
		return new DeletionResult(deleted, freed, failures);
	}

	private static IllegalStateException staleError() {
		return new IllegalStateException(
				"The archive chain changed since this deletion was reviewed; review it again");
	}

	/** The drive's archives as a deletion needs them: the merged full the chain starts from and what it made obsolete. */
	private Context context(DriveScope scope) {
		List<Archive> archives = archivePort.findByScopeKey(scope.key());
		if (archives.isEmpty()) {
			throw new IllegalStateException("This drive has no archives");
		}
		ArchiveChainInspector inspection = ArchiveChainInspector.inspect(archives, archivePort);
		if (inspection.chain().problem() != null) {
			throw new ArchiveChainException(inspection.chain().problem());
		}
		Archive root = inspection.chain().archives().getFirst();
		if (root.mode() != ArchiveMode.MERGED_FULL) {
			throw new IllegalStateException(
					"The current chain does not start from a merged full, so there is nothing to delete");
		}
		if (inspection.obsoleteIds().isEmpty()) {
			throw new IllegalStateException("There are no obsolete archives to delete");
		}
		List<Archive> obsolete = archives.stream().filter(archive -> inspection.obsoleteIds().contains(archive.id()))
				.toList();
		return new Context(root, inspection.chain().archives().getLast(), obsolete);
	}

	/** The drive's archives as removing earlier chains needs them: a sound current chain and everything outside it. */
	private EarlierChains earlierChains(DriveScope scope) {
		List<Archive> archives = archivePort.findByScopeKey(scope.key());
		if (archives.isEmpty()) {
			throw new IllegalStateException("This drive has no archives");
		}
		ArchiveChainInspector inspection = ArchiveChainInspector.inspect(archives, archivePort);
		if (inspection.chain().problem() != null) {
			throw new ArchiveChainException(inspection.chain().problem());
		}
		List<Archive> earlier = archives.stream()
				.filter(archive -> !inspection.chainIds().contains(archive.id())
						&& !inspection.obsoleteIds().contains(archive.id()))
				.toList();
		if (earlier.isEmpty()) {
			throw new IllegalStateException("There are no earlier chains to delete");
		}
		return new EarlierChains(inspection.chain().archives(), earlier);
	}

	private static void checkManifest(DriveScope scope, Archive archive, ArchiveManifest manifest, List<String> problems) {
		if (!scope.equals(manifest.scope())) {
			problems.add("Archive " + archive.archivePath() + " belongs to " + manifest.scope() + ", not " + scope);
		}
		if (manifest.sequenceNumber() != archive.sequenceNumber() || manifest.mode() != archive.mode()) {
			problems.add("Archive " + archive.archivePath() + " says it is " + manifest.mode() + " number "
					+ manifest.sequenceNumber() + " but the records say " + archive.mode() + " number "
					+ archive.sequenceNumber());
		}
	}

	/** Streams the whole entry, so a corrupt or truncated one is caught, and compares its size with the manifest. */
	private static void verifyEntry(ArchiveReader reader, ManifestFile file, List<String> problems) {
		long size;
		try (InputStream content = reader.openEntry(file.entry())) {
			size = content.transferTo(java.io.OutputStream.nullOutputStream());
		} catch (IOException exception) {
			problems.add("Entry " + file.entry() + " cannot be read: " + exception.getMessage());
			return;
		}
		if (file.sizeBytes() != null && size != file.sizeBytes()) {
			problems.add("Entry " + file.entry() + " holds " + size + " bytes but the manifest says " + file.sizeBytes());
		}
	}

	/**
	 * Decides, for every index row in the obsolete archives, whether the merged full still carries that revision (the
	 * row moves to it) or not (the row goes), and lists the files whose current content will no longer exist anywhere.
	 */
	private IndexChanges indexChanges(List<Archive> obsolete, ArchiveManifest merged, List<String> problems) {
		Map<String, ManifestFile> records = new HashMap<>();
		merged.files().forEach(record -> records.put(record.fileId(), record));
		Map<Long, String> repoint = new LinkedHashMap<>();
		List<Long> remove = new ArrayList<>();
		List<LostContent> lost = new ArrayList<>();
		int events = 0;
		for (Archive archive : obsolete) {
			events += fileEventPort.countByArchiveId(archive.id());
			for (FileCapture capture : fileCapturePort.findByArchiveId(archive.id())) {
				ManifestFile record = records.get(capture.fileId());
				if (record != null && record.entry() != null && Objects.equals(capture.revisionId(), record.revisionId())) {
					repoint.put(capture.id(), record.entry());
					continue;
				}
				remove.add(capture.id());
				classifyRemoval(capture, record, lost, problems);
			}
		}
		return new IndexChanges(repoint, remove, lost, events);
	}

	private void classifyRemoval(FileCapture capture, ManifestFile record, List<LostContent> lost, List<String> problems) {
		StoredFile file = fileMetadataPort.findByFileId(capture.fileId()).orElse(null);
		if (file == null || !Objects.equals(file.currentVersionId(), capture.id())) {
			return; // a superseded revision: history that only the old archive held, counted but not listed
		}
		if (file.trashed()) {
			lost.add(new LostContent(file.fileId(), file.name(), capture.revisionId(),
					"Trashed file: its content exists only in an obsolete archive"));
		} else if (record == null) {
			lost.add(new LostContent(file.fileId(), file.name(), capture.revisionId(),
					"Deleted from Drive: its content exists only in an obsolete archive"));
		} else if (record.entry() != null) {
			problems.add("The current version of " + file.name() + " is revision " + capture.revisionId()
					+ " but the merged archive carries revision " + record.revisionId());
		} else {
			lost.add(new LostContent(file.fileId(), file.name(), capture.revisionId(),
					"Not carried by the merged archive: its content exists only in an obsolete archive"));
		}
	}

	/**
	 * An earlier chain shares no index row with the current one (a from-scratch full captures every file again), so
	 * all of its rows go; a file whose current content is one of them was not captured by the current chain.
	 */
	private IndexChanges earlierChainChanges(List<Archive> earlier) {
		List<Long> remove = new ArrayList<>();
		List<LostContent> lost = new ArrayList<>();
		int events = 0;
		for (Archive archive : earlier) {
			events += fileEventPort.countByArchiveId(archive.id());
			for (FileCapture capture : fileCapturePort.findByArchiveId(archive.id())) {
				remove.add(capture.id());
				StoredFile file = fileMetadataPort.findByFileId(capture.fileId()).orElse(null);
				if (file != null && Objects.equals(file.currentVersionId(), capture.id())) {
					lost.add(new LostContent(file.fileId(), file.name(), capture.revisionId(), file.trashed()
							? "Trashed file: its content exists only in an earlier chain"
							: "Not captured by the current chain: its content exists only in an earlier chain"));
				}
			}
		}
		return new IndexChanges(Map.of(), remove, lost, events);
	}

	private record Context(Archive merged, Archive tip, List<Archive> obsolete) {
	}

	private record EarlierChains(List<Archive> chain, List<Archive> earlier) {

		Archive root() {
			return chain.getFirst();
		}

		Archive tip() {
			return chain.getLast();
		}
	}

	private record IndexChanges(Map<Long, String> repoint, List<Long> remove, List<LostContent> lostContent, int events) {

		static final IndexChanges EMPTY = new IndexChanges(Map.of(), List.of(), List.of(), 0);
	}
}
