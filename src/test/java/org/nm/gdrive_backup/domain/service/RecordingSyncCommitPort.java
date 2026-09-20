package org.nm.gdrive_backup.domain.service;

import java.util.ArrayList;
import java.util.List;

import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.PendingCommit;
import org.nm.gdrive_backup.domain.port.out.SyncCommitPort;

/** Records commits and returns the archive with an id, as the SQLite adapter would. */
class RecordingSyncCommitPort implements SyncCommitPort {

	final List<String> log;
	final List<PendingCommit> commits = new ArrayList<>();

	RecordingSyncCommitPort(List<String> log) {
		this.log = log;
	}

	@Override
	public Archive commit(PendingCommit commit) {
		commits.add(commit);
		log.add("commit");
		Archive archive = commit.archiveOrNull();
		return archive == null ? null : new Archive(100L, archive.scopeKey(), archive.scopeType(), archive.sequenceNumber(),
				archive.baseArchiveId(), archive.mode(), archive.revisionMode(), archive.createdAt(),
				archive.archivePath(), archive.fromPageToken(), archive.toPageToken(), archive.cancelled());
	}
}
