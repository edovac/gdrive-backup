package org.nm.gdrive_backup.domain.model;

/**
 * Drive's content for one file could not be fetched. It is about that file only, so a run can skip the file and go
 * on; anything else (credentials, quota, writing the archive) is not this exception and still ends the run.
 */
public class FileDownloadException extends IllegalStateException {

	public FileDownloadException(String message, Throwable cause) {
		super(message, cause);
	}
}
