package org.nm.gdrive_backup.domain.service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveManifest;
import org.nm.gdrive_backup.domain.model.ArchiveManifest.ManifestFile;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.ArchiveReader;
import org.nm.gdrive_backup.domain.model.AvailableDrive;
import org.nm.gdrive_backup.domain.model.DatabaseRebuildResult;
import org.nm.gdrive_backup.domain.model.DatabaseStatus;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.DriveScopeType;
import org.nm.gdrive_backup.domain.model.FileCapture;
import org.nm.gdrive_backup.domain.model.FileEvent;
import org.nm.gdrive_backup.domain.model.PendingCommit;
import org.nm.gdrive_backup.domain.model.StoredDrive;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.model.SyncState;
import org.nm.gdrive_backup.domain.port.in.DatabaseRebuildUseCase;
import org.nm.gdrive_backup.domain.port.out.ArchiveReaderPort;
import org.nm.gdrive_backup.domain.port.out.ArchiveScanPort;
import org.nm.gdrive_backup.domain.port.out.DatabaseRebuildPort;
import org.nm.gdrive_backup.domain.port.out.DriveMetadataPort;
import org.nm.gdrive_backup.domain.port.out.SyncCommitPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recreates the database from the archives' own manifests. Each archive, oldest first within its drive, is committed
 * the way the run that wrote it committed it (its files, events and captures, then the cursor of the newest one),
 * so everything that reads the database afterwards, including the next incremental backup, sees the same shape.
 * Only the archives are read: Google, and whatever is left of the old database, play no part.
 */
public class DatabaseRebuildService implements DatabaseRebuildUseCase {

	private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseRebuildService.class);

	private final ArchiveScanPort scanPort;
	private final ArchiveReaderPort readerPort;
	private final DatabaseRebuildPort databasePort;
	private final SyncCommitPort syncCommitPort;
	private final DriveMetadataPort driveMetadataPort;
	private final BackupActivity backupActivity;
	private final BackupProgressTracker progressTracker;
	private final BackupCancellation cancellation;

	public DatabaseRebuildService(ArchiveScanPort scanPort, ArchiveReaderPort readerPort,
			DatabaseRebuildPort databasePort, SyncCommitPort syncCommitPort, DriveMetadataPort driveMetadataPort,
			BackupActivity backupActivity, BackupProgressTracker progressTracker, BackupCancellation cancellation) {
		this.scanPort = scanPort;
		this.readerPort = readerPort;
		this.databasePort = databasePort;
		this.syncCommitPort = syncCommitPort;
		this.driveMetadataPort = driveMetadataPort;
		this.backupActivity = backupActivity;
		this.progressTracker = progressTracker;
		this.cancellation = cancellation;
	}

	@Override
	public DatabaseStatus status() {
		return databasePort.status();
	}

	@Override
	public DatabaseRebuildResult rebuild(boolean replaceUsable) {
		return backupActivity.duringExclusiveOperation(() -> {
			if (!replaceUsable && databasePort.status() == DatabaseStatus.USABLE) {
				throw new IllegalStateException("The database is usable; confirm before replacing it");
			}
			LOGGER.info("Database rebuild started (current database: {}, replacing a usable one: {})",
					databasePort.status(), replaceUsable);
			cancellation.begin();
			progressTracker.jobStarted(List.of(new AvailableDrive("database", "Database rebuild", false)));
			progressTracker.driveStarted(new AvailableDrive("database", "Database rebuild", false));
			try {
				DatabaseRebuildResult result = rebuildNow();
				progressTracker.driveCompleted();
				return result;
			} catch (RuntimeException exception) {
				LOGGER.error("Database rebuild failed; the current database was left as it was", exception);
				throw exception;
			} finally {
				progressTracker.jobFinished();
			}
		});
	}

	private DatabaseRebuildResult rebuildNow() {
		progressTracker.enumerating();
		List<String> problems = new ArrayList<>();
		Map<DriveScope, List<Found>> byScope = readManifests(problems);
		if (byScope == null) {
			LOGGER.info("Database rebuild cancelled while reading manifests; the database was not changed");
			return DatabaseRebuildResult.cancelledRun();
		}
		int total = byScope.values().stream().mapToInt(List::size).sum();
		LOGGER.info("Read {} archive manifest(s) for {} drive(s); {} archive(s) could not be used", total, byScope.size(),
				problems.size());
		progressTracker.enumerated(total);

		databasePort.startFresh();
		boolean swapped = false;
		try {
			Replay replay = new Replay(problems);
			for (Map.Entry<DriveScope, List<Found>> scope : byScope.entrySet()) {
				if (!replay.replayScope(scope.getKey(), scope.getValue())) {
					LOGGER.info("Database rebuild cancelled while restoring {}; the database was not changed",
							scope.getKey().key());
					return DatabaseRebuildResult.cancelledRun();
				}
			}
			String previous = databasePort.complete().orElse(null);
			swapped = true;
			LOGGER.info("Database rebuild finished: {} archive(s) of {} drive(s) restored, {} problem(s)",
					replay.archivesRestored, byScope.size(), problems.size());
			replay.scopesWithoutCursor.forEach(key -> LOGGER.info(
					"No change cursor restored for {}; its next incremental backup starts with a full inventory", key));
			return new DatabaseRebuildResult(false, replay.archivesRestored, byScope.size(), List.copyOf(problems),
					List.copyOf(replay.scopesWithoutCursor), previous);
		} finally {
			if (!swapped) {
				databasePort.abort();
			}
		}
	}

	/** Every readable manifest grouped by drive and ordered by sequence number; null when cancelled meanwhile. */
	private Map<DriveScope, List<Found>> readManifests(List<String> problems) {
		List<String> paths;
		try {
			paths = scanPort.listArchivePaths();
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to list the archives: " + exception.getMessage(), exception);
		}
		Map<DriveScope, List<Found>> byScope = new LinkedHashMap<>();
		for (String path : paths) {
			if (cancellation.isImmediateStopRequested()) {
				return null;
			}
			try (ArchiveReader reader = readerPort.open(path)) {
				ArchiveManifest manifest = reader.manifest();
				byScope.computeIfAbsent(manifest.scope(), key -> new ArrayList<>()).add(new Found(path, manifest));
			} catch (IOException | RuntimeException exception) {
				LOGGER.error("Archive {} cannot be read and is left out of the rebuild", path, exception);
				problems.add(path + " was skipped: " + exception.getMessage());
			}
		}
		byScope.values().forEach(list -> list.sort(Comparator.comparingInt(found -> found.manifest().sequenceNumber())));
		return byScope;
	}

	private record Found(String path, ArchiveManifest manifest) {
	}

	/** The state a replay carries from one archive to the next. */
	private final class Replay {

		private final List<String> problems;
		private final List<String> scopesWithoutCursor = new ArrayList<>();
		private final Set<String> knownFileIds = new HashSet<>();
		private int archivesRestored;

		Replay(List<String> problems) {
			this.problems = problems;
		}

		/** Returns false when the run was cancelled part-way. */
		boolean replayScope(DriveScope scope, List<Found> archives) {
			Map<Integer, Archive> savedBySequence = new HashMap<>();
			Map<String, String> headRevisionByFile = new HashMap<>();
			Set<String> capturedRevisions = new HashSet<>();
			Archive newest = null;
			// Duplicates go first, so the archive that carries the cursor is always one that is restored.
			Set<Integer> seen = new HashSet<>();
			List<Found> unique = new ArrayList<>();
			for (Found found : archives) {
				if (seen.add(found.manifest().sequenceNumber())) {
					unique.add(found);
				} else {
					LOGGER.error("{} repeats archive number {} of {}; it is left out of the rebuild", found.path(),
							found.manifest().sequenceNumber(), scope.key());
					problems.add(found.path() + " was skipped: archive number " + found.manifest().sequenceNumber()
							+ " already exists for this drive");
				}
			}
			archives = unique;
			LOGGER.info("Restoring {} ({}): {} archive(s)", scope.key(), scope.type(), archives.size());
			for (Found found : archives) {
				if (cancellation.isImmediateStopRequested()) {
					return false;
				}
				ArchiveManifest manifest = found.manifest();
				progressTracker.itemProcessed(found.path());
				Archive base = null;
				if (manifest.baseSequenceNumber() != null) {
					base = savedBySequence.get(manifest.baseSequenceNumber());
					if (base == null) {
						LOGGER.error("{} chains onto archive {} of {}, which was not found; restoring it without that link",
								found.path(), manifest.baseSequenceNumber(), scope.key());
						problems.add(found.path() + " chains onto archive " + manifest.baseSequenceNumber()
								+ ", which was not found; it is restored without that link");
					}
				}
				boolean last = found == archives.getLast();
				SyncState cursor = last && manifest.toPageToken() != null
						? new SyncState(scope.key(), manifest.toPageToken()) : null;
				if (last && cursor == null) {
					scopesWithoutCursor.add(scope.key());
				}
				Archive archive = new Archive(null, scope.key(), scope.type(), manifest.sequenceNumber(),
						base == null ? null : base.id(), manifest.mode(), manifest.revisionMode(), manifest.createdAt(),
						found.path(), manifest.fromPageToken(), manifest.toPageToken(), false);
				PendingCommit commit = commitFor(scope, manifest, archive, cursor, savedBySequence, headRevisionByFile,
						capturedRevisions);
				Archive saved = syncCommitPort.commit(commit);
				savedBySequence.put(manifest.sequenceNumber(), saved);
				archivesRestored++;
				LOGGER.info("Restored archive {} ({}) of {}: {} file record(s), {} content record(s), {} event(s)",
						manifest.sequenceNumber(), manifest.mode(), scope.key(), commit.files().size(),
						commit.captures().size(), commit.events().size());
				newest = saved;
			}
			if (newest != null && scope.type() == DriveScopeType.SHARED_DRIVE) {
				driveMetadataPort.save(new StoredDrive(scope.key(), driveName(scope, newest.archivePath()), null));
			}
			return true;
		}

		private PendingCommit commitFor(DriveScope scope, ArchiveManifest manifest, Archive archive, SyncState cursor,
				Map<Integer, Archive> savedBySequence, Map<String, String> headRevisionByFile,
				Set<String> capturedRevisions) {
			List<StoredFile> files = new ArrayList<>();
			List<FileCapture> captures = new ArrayList<>();
			for (ManifestFile record : manifest.files()) {
				if (record.removed()) {
					continue;
				}
				if (record.entry() != null && record.revisionId() != null) {
					headRevisionByFile.put(record.fileId(), record.revisionId());
					// A merged full holds bytes an older archive may already have captured; only a file whose bytes
					// would otherwise have no capture gets one (the same row a deletion of the older archives leaves).
					boolean firstSight = capturedRevisions.add(record.fileId() + '\0' + record.revisionId());
					if (manifest.mode() != ArchiveMode.MERGED_FULL || firstSight) {
						captures.add(new FileCapture(null, record.fileId(), record.revisionId(), manifest.createdAt(), null,
								record.entry(), record.sizeBytes() == null ? 0 : record.sizeBytes()));
					}
				}
				files.add(new StoredFile(record.fileId(), scope.key(), record.name(), String.join(",", record.parents()),
						record.driveId(), record.mimeType(), record.trashed(), headRevisionByFile.get(record.fileId()),
						null));
				knownFileIds.add(record.fileId());
			}
			List<FileEvent> events = new ArrayList<>();
			for (ArchiveManifest.ManifestEvent event : manifest.events()) {
				// An event for a file no surviving manifest describes (its archive was merged away) has nothing to hang on.
				if (knownFileIds.contains(event.fileId())) {
					events.add(new FileEvent(null, event.fileId(), event.eventType(), event.oldValue(), event.newValue(),
							event.timestamp() == null ? Instant.EPOCH : event.timestamp(), null));
				} else {
					LOGGER.info("Skipped the {} event for file {} in archive {} of {}: no archive describes that file, "
							+ "so there is no record to attach it to", event.eventType(), event.fileId(),
							manifest.sequenceNumber(), scope.key());
				}
			}
			List<Long> sources = manifest.sourceArchives().stream()
					.map(source -> savedBySequence.get(source.sequenceNumber()))
					.filter(saved -> saved != null)
					.map(Archive::id)
					.toList();
			return new PendingCommit(archive, files, events, captures, cursor, sources);
		}

		/** A Shared Drive's name from its archive folder, {@code <name> (<drive_id>)}, falling back to the id. */
		private static String driveName(DriveScope scope, String archivePath) {
			String[] parts = archivePath.split("/");
			String folder = parts.length >= 2 ? parts[parts.length - 2] : scope.key();
			String suffix = " (" + scope.key() + ")";
			return folder.endsWith(suffix) && folder.length() > suffix.length()
					? folder.substring(0, folder.length() - suffix.length()) : scope.key();
		}
	}
}
