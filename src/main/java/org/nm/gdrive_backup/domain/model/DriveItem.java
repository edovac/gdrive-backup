package org.nm.gdrive_backup.domain.model;

public record DriveItem(
		String id,
		String name,
		String mimeType,
		String driveId,
		boolean folder,
		boolean trashed) {
}