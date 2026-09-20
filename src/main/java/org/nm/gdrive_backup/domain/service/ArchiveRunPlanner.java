package org.nm.gdrive_backup.domain.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.RevisionMode;
import org.nm.gdrive_backup.domain.port.out.ArchivePort;

/** Decides where a run's archive goes and how it chains, before any content is streamed. */
public class ArchiveRunPlanner {

	private final ArchivePort archivePort;

	public ArchiveRunPlanner(ArchivePort archivePort) {
		this.archivePort = archivePort;
	}

	public Plan planFull(DriveScope scope, String scopeDisplayNameOrNull) {
		List<Archive> existing = archivePort.findByScopeKey(scope.key());
		return plan(scope, scopeDisplayNameOrNull, ArchiveMode.FULL, nextSequenceNumber(existing), null, null);
	}

	/** A merged full is a new chain root: no base, numbered after everything the scope already has. */
	public Plan planMergedFull(DriveScope scope, String scopeDisplayNameOrNull) {
		List<Archive> existing = archivePort.findByScopeKey(scope.key());
		return plan(scope, scopeDisplayNameOrNull, ArchiveMode.MERGED_FULL, nextSequenceNumber(existing), null, null);
	}

	public Plan planIncremental(DriveScope scope, String scopeDisplayNameOrNull) {
		List<Archive> existing = archivePort.findByScopeKey(scope.key());
		Archive base = existing.stream()
				.max(Comparator.comparingInt(Archive::sequenceNumber))
				.orElseThrow(() -> new IllegalStateException(
						"Incremental archive for " + scope.key() + " has no prior archive to chain from"));
		return plan(scope, scopeDisplayNameOrNull, ArchiveMode.INCREMENTAL, nextSequenceNumber(existing), base.id(),
				base.sequenceNumber());
	}

	private static Plan plan(DriveScope scope, String scopeDisplayNameOrNull, ArchiveMode mode, int sequenceNumber,
			Long baseArchiveId, Integer baseSequenceNumber) {
		String path = "archives/" + ArchiveNaming.scopeFolderName(scope, scopeDisplayNameOrNull) + "/"
				+ ArchiveNaming.archiveFileName(sequenceNumber, mode);
		return new Plan(scope, mode, sequenceNumber, baseArchiveId, baseSequenceNumber, path);
	}

	private static int nextSequenceNumber(List<Archive> existing) {
		// No chain_id exists yet, so this counts across the scope's whole archive history,
		// not per chain; revisit once "start a new chain on a fresh full backup" is built.
		return existing.stream().mapToInt(Archive::sequenceNumber).max().orElse(0) + 1;
	}

	public record Plan(DriveScope scope, ArchiveMode mode, int sequenceNumber, Long baseArchiveId,
			Integer baseSequenceNumber, String relativeTargetPath) {

		public Archive toArchive(Instant createdAt, String fromPageToken, String toPageToken) {
			return new Archive(null, scope.key(), scope.type(), sequenceNumber, baseArchiveId, mode, RevisionMode.LATEST_ONLY,
					createdAt, relativeTargetPath, fromPageToken, toPageToken, false);
		}
	}
}
