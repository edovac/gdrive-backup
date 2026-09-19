package org.nm.gdrive_backup.domain.service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveManifest;
import org.nm.gdrive_backup.domain.model.ArchiveManifest.ManifestFile;
import org.nm.gdrive_backup.domain.model.ArchiveSession;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.FileCapture;
import org.nm.gdrive_backup.domain.model.InitialSyncResult;
import org.nm.gdrive_backup.domain.model.PendingCommit;
import org.nm.gdrive_backup.domain.model.RevisionMode;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.model.SyncState;
import org.nm.gdrive_backup.domain.port.in.InitialDriveSyncUseCase;
import org.nm.gdrive_backup.domain.port.out.ArchiveSessionPort;
import org.nm.gdrive_backup.domain.port.out.DriveChangePort;
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

	public InitialDriveSyncService(DriveFileListingPort fileListingPort, DriveChangePort changePort,
			FileContentStreamingService contentStreamingService, ArchiveSessionPort archiveSessionPort,
			ArchiveRunPlanner archiveRunPlanner, SyncCommitPort syncCommitPort, BackupProgressTracker progressTracker,
			BackupCancellation cancellation) {
		this.fileListingPort = fileListingPort;
		this.changePort = changePort;
		this.contentStreamingService = contentStreamingService;
		this.archiveSessionPort = archiveSessionPort;
		this.archiveRunPlanner = archiveRunPlanner;
		this.syncCommitPort = syncCommitPort;
		this.progressTracker = progressTracker;
		this.cancellation = cancellation;
	}

	@Override
	public InitialSyncResult synchronize(ServiceAccountAccess access, DriveScope scope, String scopeDisplayNameOrNull) {
		progressTracker.enumerating();
		// Fetched before listing: a full run can take hours, and changes made meanwhile must be replayed
		// by the next incremental run rather than lost between the listing and the baseline.
		String pageToken = changePort.getStartPageToken(access, scope);
		List<StoredFile> files = fileListingPort.listAllFiles(access, scope);
		progressTracker.enumerated(files.size());

		FlatTreePathResolver resolver = new FlatTreePathResolver(namesWithExportExtensions(files));
		ArchiveRunPlanner.Plan plan = archiveRunPlanner.planFull(scope, scopeDisplayNameOrNull);
		List<FileCapture> captures = new ArrayList<>();
		try (ArchiveSession session = archiveSessionPort.open(plan.relativeTargetPath())) {
			int processed = 0;
			for (StoredFile file : files) {
				if (cancellation.isImmediateStopRequested()) {
					return new InitialSyncResult(scope, processed, null, null, true);
				}
				if (isEligible(file)) {
					captures.add(contentStreamingService.stream(access, file, session,
							resolver.resolveEntryName(file)));
				}
				progressTracker.itemProcessed(file.name());
				processed++;
			}
			progressTracker.packaging();
			Instant createdAt = Instant.now();
			Archive archive = plan.toArchive(createdAt, null, null);
			session.publish(new ArchiveManifest(scope.key(), plan.mode(), RevisionMode.LATEST_ONLY,
					plan.sequenceNumber(), null, createdAt, null, null, manifestFiles(captures), List.of()));
			Archive saved = syncCommitPort.commit(new PendingCommit(archive, files, List.of(), captures,
					new SyncState(scope.key(), pageToken)));
			return new InitialSyncResult(scope, files.size(), pageToken, saved, false);
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to write archive " + plan.relativeTargetPath(), exception);
		}
	}

	private static boolean isEligible(StoredFile file) {
		return !file.trashed() && FileContentStreamingService.hasBackableContent(file);
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
				file.trashed(), file.headRevisionId(), file.currentVersionId());
	}

	private static List<ManifestFile> manifestFiles(List<FileCapture> captures) {
		return captures.stream()
				.map(capture -> new ManifestFile(capture.fileId(), capture.entryName(), capture.revisionId(),
						capture.sizeBytes()))
				.toList();
	}
}
