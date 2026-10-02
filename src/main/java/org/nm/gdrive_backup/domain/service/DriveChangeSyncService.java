package org.nm.gdrive_backup.domain.service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntSupplier;

import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveManifest;
import org.nm.gdrive_backup.domain.model.ArchiveManifest.ManifestEvent;
import org.nm.gdrive_backup.domain.model.ArchiveManifest.ManifestFile;
import org.nm.gdrive_backup.domain.model.ArchiveSession;
import org.nm.gdrive_backup.domain.model.DriveChange;
import org.nm.gdrive_backup.domain.model.DriveChangePage;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.FileCapture;
import org.nm.gdrive_backup.domain.model.FileEvent;
import org.nm.gdrive_backup.domain.model.StreamedFile;
import org.nm.gdrive_backup.domain.model.PendingCommit;
import org.nm.gdrive_backup.domain.model.RevisionMode;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.model.SyncResult;
import org.nm.gdrive_backup.domain.model.SyncState;
import org.nm.gdrive_backup.domain.port.in.DriveChangeSyncUseCase;
import org.nm.gdrive_backup.domain.port.out.ArchiveSessionPort;
import org.nm.gdrive_backup.domain.port.out.DriveChangePort;
import org.nm.gdrive_backup.domain.port.out.FileMetadataPort;
import org.nm.gdrive_backup.domain.port.out.SyncStatePort;
import org.nm.gdrive_backup.domain.port.out.SyncCommitPort;

/**
 * Applies Drive's change feed from the saved cursor. Metadata, events and the new cursor are held in memory;
 * changed content is streamed into a delta archive after the feed is exhausted, and everything is committed
 * only once that archive is published, so an interrupted run replays from the last committed cursor.
 */
public class DriveChangeSyncService implements DriveChangeSyncUseCase {

	private final DriveChangePort changePort;
	private final SyncStatePort syncStatePort;
	private final FileMetadataPort fileMetadataPort;
	private final FileContentStreamingService contentStreamingService;
	private final ArchiveSessionPort archiveSessionPort;
	private final ArchiveRunPlanner archiveRunPlanner;
	private final SyncCommitPort syncCommitPort;
	private final BackupProgressTracker progressTracker;
	private final BackupCancellation cancellation;
	private final IntSupplier downloadConcurrency;

	public DriveChangeSyncService(DriveChangePort changePort, SyncStatePort syncStatePort,
			FileMetadataPort fileMetadataPort, FileContentStreamingService contentStreamingService,
			ArchiveSessionPort archiveSessionPort, ArchiveRunPlanner archiveRunPlanner, SyncCommitPort syncCommitPort,
			BackupProgressTracker progressTracker, BackupCancellation cancellation, IntSupplier downloadConcurrency) {
		this.changePort = changePort;
		this.syncStatePort = syncStatePort;
		this.fileMetadataPort = fileMetadataPort;
		this.contentStreamingService = contentStreamingService;
		this.archiveSessionPort = archiveSessionPort;
		this.archiveRunPlanner = archiveRunPlanner;
		this.syncCommitPort = syncCommitPort;
		this.progressTracker = progressTracker;
		this.cancellation = cancellation;
		this.downloadConcurrency = downloadConcurrency;
	}

	@Override
	public SyncResult synchronize(ServiceAccountAccess access, DriveScope scope, String scopeDisplayNameOrNull) {
		String fromPageToken = syncStatePort.findByScopeKey(scope.key())
				.map(SyncState::pageToken)
				.orElseGet(() -> changePort.getStartPageToken(access, scope));
		PendingChanges pending = new PendingChanges();
		String pageToken = fromPageToken;
		int changeCount = 0;
		String newStartPageToken = null;
		while (newStartPageToken == null) {
			if (cancellation.isImmediateStopRequested()) {
				return new SyncResult(scope, changeCount, null, null, true);
			}
			DriveChangePage page = changePort.listChanges(access, scope, pageToken);
			changeCount += page.changes().size();
			page.changes().forEach(change -> {
				pending.apply(change);
				progressTracker.itemProcessed(itemLabel(change));
			});
			newStartPageToken = page.newStartPageToken();
			if (newStartPageToken == null && (page.nextPageToken() == null || page.nextPageToken().isBlank())) {
				throw new IllegalStateException("Drive change page has neither next nor new start token");
			}
			pageToken = newStartPageToken != null ? newStartPageToken : page.nextPageToken();
		}
		if (cancellation.isImmediateStopRequested()) {
			return new SyncResult(scope, changeCount, null, null, true);
		}

		SyncState newState = new SyncState(scope.key(), pageToken);
		List<StoredFile> files = new ArrayList<>(pending.files.values());
		if (!pending.hasAnythingToArchive()) {
			syncCommitPort.commit(new PendingCommit(null, files, List.of(), List.of(), newState));
			return new SyncResult(scope, changeCount, pageToken, null, false);
		}

		ArchiveRunPlanner.Plan plan = archiveRunPlanner.planIncremental(scope, scopeDisplayNameOrNull);
		Map<String, StreamedFile> streamedByFileId = new LinkedHashMap<>();
		try (ArchiveSession session = archiveSessionPort.open(plan.relativeTargetPath())) {
			// Read per run, so a change in Settings applies to the next run and never mid-run.
			ParallelContentFetcher contentFetcher = new ParallelContentFetcher(downloadConcurrency.getAsInt());
			boolean completed = contentFetcher.process(List.copyOf(pending.contentFileIds), fileId -> true,
					fileId -> contentStreamingService.fetch(access, pending.files.get(fileId), session),
					cancellation::isImmediateStopRequested,
					(fileId, fetched) -> streamedByFileId.put(fileId, contentStreamingService.write(
							pending.files.get(fileId), fetched, session, "content/" + fileId)));
			if (!completed) {
				return new SyncResult(scope, changeCount, null, null, true);
			}
			progressTracker.packaging();
			Instant createdAt = Instant.now();
			Archive archive = plan.toArchive(createdAt, fromPageToken, pageToken);
			session.publish(new ArchiveManifest(scope, plan.mode(), RevisionMode.LATEST_ONLY, plan.sequenceNumber(),
					plan.baseSequenceNumber(), createdAt, fromPageToken, pageToken, List.of(),
					manifestFiles(pending, streamedByFileId), manifestEvents(pending.events)));
			List<FileCapture> captures = streamedByFileId.values().stream().map(StreamedFile::capture).toList();
			Archive saved = syncCommitPort.commit(new PendingCommit(archive, files, pending.events, captures, newState));
			return new SyncResult(scope, changeCount, pageToken, saved, false);
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to write archive " + plan.relativeTargetPath(), exception);
		}
	}

	private static String itemLabel(DriveChange change) {
		return change.file() != null ? change.file().name() : change.fileId();
	}

	/** One record per file the run touched, then a removed record per file Drive reported as removed. */
	private static List<ManifestFile> manifestFiles(PendingChanges pending, Map<String, StreamedFile> streamedByFileId) {
		List<ManifestFile> records = new ArrayList<>();
		for (StoredFile file : pending.files.values()) {
			if (!pending.removedFileIds.contains(file.fileId())) {
				records.add(ManifestFile.of(file, streamedByFileId.get(file.fileId())));
			}
		}
		pending.removedFileIds.forEach(fileId -> records.add(ManifestFile.removed(fileId)));
		return records;
	}

	private static List<ManifestEvent> manifestEvents(List<FileEvent> events) {
		return events.stream()
				.map(event -> new ManifestEvent(event.fileId(), event.eventType(), event.oldValue(), event.newValue(),
						event.timestamp()))
				.toList();
	}

	/** In-memory overlay over the database: later changes to a file diff against what earlier ones in this run produced. */
	private final class PendingChanges {

		final Map<String, StoredFile> files = new LinkedHashMap<>();
		final List<FileEvent> events = new ArrayList<>();
		final Set<String> contentFileIds = new LinkedHashSet<>();
		final Set<String> removedFileIds = new LinkedHashSet<>();
		private boolean sawNewFile;

		/**
		 * A file seen for the first time changes the tree even with no event and no content (a new folder, a Form),
		 * and an archives-only merge needs it, so it counts as something to archive.
		 */
		boolean hasAnythingToArchive() {
			return !events.isEmpty() || !contentFileIds.isEmpty() || !removedFileIds.isEmpty() || sawNewFile;
		}

		void apply(DriveChange change) {
			if (change.removed()) {
				events.add(event(change.fileId(), "delete", null, null));
				contentFileIds.remove(change.fileId());
				removedFileIds.add(change.fileId());
				return;
			}
			StoredFile current = change.file();
			if (current == null) {
				return;
			}
			Optional<StoredFile> previous = Optional.ofNullable(files.get(current.fileId()))
					.or(() -> fileMetadataPort.findByFileId(current.fileId()));
			removedFileIds.remove(current.fileId());
			sawNewFile |= previous.isEmpty();
			previous.ifPresent(old -> recordDifferences(old, current));
			// current_version_id is assigned at commit from the captures, so the incoming value (null) is
			// replaced by the one the previous row carried, keeping "has this file ever been captured" true.
			files.put(current.fileId(), withCurrentVersion(current, previous.map(StoredFile::currentVersionId).orElse(null)));
			if (shouldBackUpContent(previous, current)) {
				contentFileIds.add(current.fileId());
			}
		}

		private boolean shouldBackUpContent(Optional<StoredFile> previous, StoredFile current) {
			// An untrashed file is re-captured even at an unchanged revision: a full run leaves trashed files out of
			// its archive, so the chain being written may not hold their bytes although an older archive does.
			return FileContentStreamingService.hasBackableContent(current)
					&& previous.map(file -> !Objects.equals(file.headRevisionId(), current.headRevisionId())
							|| file.trashed() && !current.trashed()
							|| file.currentVersionId() == null && !contentFileIds.contains(current.fileId()))
							.orElse(true);
		}

		private void recordDifferences(StoredFile previous, StoredFile current) {
			if (!previous.name().equals(current.name())) {
				events.add(event(current.fileId(), "rename", previous.name(), current.name()));
			}
			if (!previous.parents().equals(current.parents()) || !Objects.equals(previous.driveId(), current.driveId())) {
				events.add(event(current.fileId(), "move", previous.parents(), current.parents()));
			}
			if (previous.trashed() != current.trashed()) {
				events.add(event(current.fileId(), current.trashed() ? "trash" : "untrash",
						Boolean.toString(previous.trashed()), Boolean.toString(current.trashed())));
			}
			if (!Objects.equals(previous.headRevisionId(), current.headRevisionId())) {
				events.add(event(current.fileId(), "content", previous.headRevisionId(), current.headRevisionId()));
			}
		}

		private FileEvent event(String fileId, String eventType, String oldValue, String newValue) {
			return new FileEvent(null, fileId, eventType, oldValue, newValue, Instant.now(), null);
		}

		private StoredFile withCurrentVersion(StoredFile file, Long versionId) {
			return new StoredFile(file.fileId(), file.ownerScope(), file.name(), file.parents(), file.driveId(),
					file.mimeType(), file.trashed(), file.headRevisionId(), versionId);
		}
	}
}
