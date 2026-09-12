package org.nm.gdrive_backup.domain.model;

import java.io.IOException;

public class DriveExportLimitException extends IOException {

	public DriveExportLimitException(String message, Throwable cause) {
		super(message, cause);
	}
}