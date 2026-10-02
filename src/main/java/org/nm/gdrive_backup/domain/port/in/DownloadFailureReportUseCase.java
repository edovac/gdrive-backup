package org.nm.gdrive_backup.domain.port.in;

import java.util.List;

import org.nm.gdrive_backup.domain.model.DownloadFailure;

/** The files backups skipped because their content could not be downloaded, and that are still not backed up. */
public interface DownloadFailureReportUseCase {

	/** Every open failure, newest first. */
	List<DownloadFailure> openFailures();
}
