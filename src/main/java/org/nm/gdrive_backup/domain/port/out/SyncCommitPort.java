package org.nm.gdrive_backup.domain.port.out;

import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.PendingCommit;

public interface SyncCommitPort {

	/** Applies the run's database effects atomically; returns the saved archive, or null when there was none. */
	Archive commit(PendingCommit commit);
}
