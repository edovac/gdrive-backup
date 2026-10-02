package org.nm.gdrive_backup.domain.service;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveManifest;
import org.nm.gdrive_backup.domain.model.ArchiveManifest.ManifestFile;
import org.nm.gdrive_backup.domain.model.ArchiveSession;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.DownloadFailure;
import org.nm.gdrive_backup.domain.model.FailureChanges;
import org.nm.gdrive_backup.domain.model.FetchedFile;
import org.nm.gdrive_backup.domain.model.FileDownloadException;
import org.nm.gdrive_backup.domain.model.FileCapture;
import org.nm.gdrive_backup.domain.model.InitialSyncResult;
import org.nm.gdrive_backup.domain.model.PendingCommit;
import org.nm.gdrive_backup.domain.model.PersonalDriveContent;
import org.nm.gdrive_backup.domain.model.RevisionMode;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.model.StreamedFile;
import org.nm.gdrive_backup.domain.model.SyncState;
import org.nm.gdrive_backup.domain.port.in.InitialDriveSyncUseCase;
import org.nm.gdrive_backup.domain.port.out.ArchiveSessionPort;
import org.nm.gdrive_backup.domain.port.out.DriveChangePort;
import org.nm.gdrive_backup.domain.port.out.DownloadFailurePort;
import org.nm.gdrive_backup.domain.port.out.DriveFileListingPort;
import org.nm.gdrive_backup.domain.port.out.SyncCommitPort;

/**
 * Builds a full archive from scratch: every live file is re-downloaded into a Drive-shaped ZIP, and the
 * run's database effects are committed only after that ZIP is published.
 */
public class InitialDriveSyncService implements InitialDriveSyncUseCase {

	private final DriveFileListingPort fileListingPort;
	private final DriveChangePort changePort;
	private final FileContentStreamingService contentStreamingService;
	private final ArchiveSessionPort archiveSessionPort;
	private final ArchiveRunPlanner archiveRunPlanner;
	private final SyncCommitPort syncCommitPort;
	private final BackupProgressTracker progressTracker;
	private final BackupCancellation cancellation;
	private final IntSupplier downloadConcurrency;
	private final Supplier<PersonalDriveContent> personalDriveContent;
	private final DownloadFailurePort failurePort;
	private final IntSupplier maxDownloadFailures;

	public InitialDriveSyncService(DriveFileListingPort fileListingPort, DriveChangePort changePort,
			FileContentStreamingService contentStreamingService, ArchiveSessionPort archiveSessionPort,
			ArchiveRunPlanner archiveRunPlanner, SyncCommitPort syncCommitPort, BackupProgressTracker progressTracker,
			BackupCancellation cancellation, IntSupplier downloadConcurrency,
			Supplier<PersonalDriveContent> personalDriveContent) {
		this(fileListingPort, changePort, contentStreamingService, archiveSessionPort, archiveRunPlanner,
				syncCommitPort, progressTracker, cancellation, downloadConcurrency, personalDriveContent,
				NoDownloadFailures.INSTANCE, () -> DownloadFailureLimit.DEFAULT_MAX_FAILURES);
	}

	public InitialDriveSyncService(DriveFileListingPort fileListingPort, DriveChangePort changePort,
			FileContentStreamingService contentStreamingService, ArchiveSessionPort archiveSessionPort,
			ArchiveRunPlanner archiveRunPlanner, SyncCommitPort syncCommitPort, BackupProgressTracker progressTracker,
			BackupCancellation cancellation, IntSupplier downloadConcurrency,
			Supplier<PersonalDriveContent> personalDriveContent, DownloadFailurePort failurePort,
			IntSupplier maxDownloadFailures) {
		this.failurePort = failurePort;
		this.maxDownloadFailures = maxDownloadFailures;
		this.fileListingPort = fileListingPort;
		this.changePort = changePort;
		this.contentStreamingService = contentStreamingService;
		this.archiveSessionPort = archiveSessionPort;
		this.archiveRunPlanner = archiveRunPlanner;
		this.syncCommitPort = syncCommitPort;
		this.progressTracker = progressTracker;
		this.cancellation = cancellation;
		this.downloadConcurrency = downloadConcurrency;
		this.personalDriveContent = personalDriveContent;
	}

	@Override
	public InitialSyncResult synchronize(ServiceAccountAccess access, DriveScope scope, String scopeDisplayNameOrNull) {
		progressTracker.enumerating();
		// Fetched before listing: a full run can take hours, and changes made meanwhile must be replayed
		// by the next incremental run rather than lost between the listing and the baseline.
		String pageToken = changePort.getStartPageToken(access, scope);
		// Read per run, like the download concurrency, so a change in Settings never applies mid-run.
		List<StoredFile> files = fileListingPort.listAllFiles(access, scope, personalDriveContent.get());
		progressTracker.enumerated(files.size());

		FlatTreePathResolver resolver = new FlatTreePathResolver(namesWithExportExtensions(files));
		ArchiveRunPlanner.Plan plan = archiveRunPlanner.planFull(scope, scopeDisplayNameOrNull);
		Map<String, StreamedFile> streamedByFileId = new HashMap<>();
		try (ArchiveSession session = archiveSessionPort.open(plan.relativeTargetPath())) {
			DrivePathResolver drivePaths = new DrivePathResolver(
					DrivePathResolver.rootLabel(scope, scopeDisplayNameOrNull), driveFilesById(files)::get);
			// Every entry the run is going to write, so a PDF fallback never takes a name another file is about to
			// use, whichever order the downloads finish in.
			Map<String, String> entryNames = new HashMap<>();
			for (StoredFile file : files) {
				if (isEligible(file)) {
					entryNames.put(file.fileId(), resolver.resolveEntryName(file));
				}
			}
			Set<String> reservedEntryNames = Set.copyOf(entryNames.values());
			int[] processed = {0};
			// A file whose content cannot be fetched is skipped and reported rather than failing the run; every file
			// is attempted again by each full run, so the failures open from before are re-tried here.
			Set<String> openFailureFileIds = failurePort.findOpenByScopeKey(scope.key()).stream()
					.map(DownloadFailure::fileId).collect(Collectors.toCollection(LinkedHashSet::new));
			DownloadFailureLimit failureLimit = new DownloadFailureLimit(maxDownloadFailures.getAsInt());
			// Read per run, so a change in Settings applies to the next run and never mid-run.
			ParallelContentFetcher contentFetcher = new ParallelContentFetcher(downloadConcurrency.getAsInt());
			// Downloads overlap and each is written to the archive as soon as it completes, so one very large file
			// occupies a single slot instead of holding back every file queued behind it. The entries therefore are
			// not in listing order (the manifest still is, and entry names do not depend on the order).
			boolean completed = contentFetcher.process(files, InitialDriveSyncService::isEligible,
					file -> {
						progressTracker.downloadStarted(file.fileId(), file.name(), drivePaths.pathOf(file),
								file.sizeBytes());
						FetchedFile fetched;
						try {
							fetched = contentStreamingService.fetch(access, file, session,
									bytes -> progressTracker.downloadProgressed(file.fileId(), bytes));
						} catch (FileDownloadException exception) {
							progressTracker.downloadAborted(file.fileId());
							progressTracker.itemProcessed(file.name());
							return DownloadOutcome.failed(exception);
						} catch (RuntimeException | Error exception) {
							progressTracker.downloadAborted(file.fileId());
							throw exception;
						}
						// The exact size, even for a file too quick to report while it streamed.
						progressTracker.downloadProgressed(file.fileId(), fetched.content().size());
						progressTracker.downloadFinished(file.fileId());
						progressTracker.itemProcessed(file.name());
						return DownloadOutcome.fetched(fetched);
					},
					cancellation::isImmediateStopRequested,
					(file, outcome) -> {
						if (outcome == null) {
							progressTracker.itemProcessed(file.name());
						} else if (outcome.failure() != null) {
							failureLimit.failed(DownloadFailure.found(scope.key(), file.fileId(), file.name(),
									drivePaths.pathOf(file), DownloadOutcome.reasonOf(outcome.failure()), Instant.now()));
						} else {
							failureLimit.succeeded();
							streamedByFileId.put(file.fileId(), contentStreamingService.write(file, outcome.fetched(),
									session, entryNames.get(file.fileId()), reservedEntryNames));
						}
						processed[0]++;
					});
			if (!completed) {
				return new InitialSyncResult(scope, processed[0], null, null, true);
			}
			List<DownloadFailure> failures = failureLimit.failures();
			// Whatever was open before and did not fail again was captured now or no longer needs a backup.
			Set<String> failedFileIds = failures.stream().map(DownloadFailure::fileId).collect(Collectors.toSet());
			List<String> resolvedFileIds = openFailureFileIds.stream().filter(id -> !failedFileIds.contains(id)).toList();
			progressTracker.packaging();
			Instant createdAt = Instant.now();
			Archive archive = plan.toArchive(createdAt, null, null);
			session.publish(new ArchiveManifest(scope, plan.mode(), RevisionMode.LATEST_ONLY, plan.sequenceNumber(),
					null, createdAt, null, null, List.of(), manifestFiles(files, streamedByFileId), List.of()));
			List<FileCapture> captures = streamedByFileId.values().stream().map(StreamedFile::capture).toList();
			Archive saved = syncCommitPort.commit(new PendingCommit(archive, files, List.of(), captures,
					new SyncState(scope.key(), pageToken), new FailureChanges(scope.key(), failures, resolvedFileIds)));
			return new InitialSyncResult(scope, files.size(), pageToken, saved, false, failures);
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to write archive " + plan.relativeTargetPath(), exception);
		}
	}

	private static boolean isEligible(StoredFile file) {
		return !file.trashed() && FileContentStreamingService.hasBackableContent(file);
	}

	/** The listing by id with the names exactly as Drive has them, for the paths shown while downloading. */
	private static Map<String, StoredFile> driveFilesById(List<StoredFile> files) {
		Map<String, StoredFile> byId = new HashMap<>();
		for (StoredFile file : files) {
			byId.put(file.fileId(), file);
		}
		return byId;
	}

	/** Native files are exported with an extension, so that is the name their siblings must not collide with. */
	private static Map<String, StoredFile> namesWithExportExtensions(List<StoredFile> files) {
		Map<String, StoredFile> byId = new HashMap<>();
		for (StoredFile file : files) {
			byId.put(file.fileId(), FileContentStreamingService.exportExtensionFor(file.mimeType())
					.filter(extension -> file.name() == null || !file.name().endsWith(extension))
					.map(extension -> withName(file, file.name() + extension))
					.orElse(file));
		}
		return byId;
	}

	private static StoredFile withName(StoredFile file, String name) {
		return new StoredFile(file.fileId(), file.ownerScope(), name, file.parents(), file.driveId(), file.mimeType(),
				file.trashed(), file.headRevisionId(), file.currentVersionId(), file.sizeBytes());
	}

	/** Every listed file, in listing order; only those streamed this run carry an entry. */
	private static List<ManifestFile> manifestFiles(List<StoredFile> files, Map<String, StreamedFile> streamedByFileId) {
		return files.stream()
				.map(file -> ManifestFile.of(file, streamedByFileId.get(file.fileId())))
				.toList();
	}
}
