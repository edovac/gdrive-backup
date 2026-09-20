package org.nm.gdrive_backup.domain.port.out;

import org.nm.gdrive_backup.domain.model.DeletionCommit;

public interface ArchiveDeletionCommitPort {

	/** Applies the whole deletion atomically; on any failure nothing changes. */
	void apply(DeletionCommit commit);
}
