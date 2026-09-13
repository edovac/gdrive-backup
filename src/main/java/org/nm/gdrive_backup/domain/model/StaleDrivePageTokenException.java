package org.nm.gdrive_backup.domain.model;

/**
 * The Drive changes cursor can no longer be used and the scope must be
 * inventoried again before a new cursor is stored.
 */
public class StaleDrivePageTokenException extends RuntimeException {

	public StaleDrivePageTokenException(String message, Throwable cause) {
		super(message, cause);
	}
}
