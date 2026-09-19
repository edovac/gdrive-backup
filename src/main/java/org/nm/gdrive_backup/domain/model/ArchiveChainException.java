package org.nm.gdrive_backup.domain.model;

/** The archive chain cannot be used as it stands: an archive is missing, unreadable, or contradicts its record. */
public class ArchiveChainException extends RuntimeException {

	public ArchiveChainException(String message) {
		super(message);
	}

	public ArchiveChainException(String message, Throwable cause) {
		super(message, cause);
	}
}
