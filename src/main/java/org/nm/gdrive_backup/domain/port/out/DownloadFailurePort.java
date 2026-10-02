package org.nm.gdrive_backup.domain.port.out;

import java.util.List;

import org.nm.gdrive_backup.domain.model.DownloadFailure;

/** Reads the persistent report of skipped files; a run writes it through {@link SyncCommitPort}. */
public interface DownloadFailurePort {

	/** The open failures of one drive, oldest first. */
	List<DownloadFailure> findOpenByScopeKey(String scopeKey);

	/** The open failures of every drive, newest first. */
	List<DownloadFailure> findOpen();
}
