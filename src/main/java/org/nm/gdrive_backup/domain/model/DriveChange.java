package org.nm.gdrive_backup.domain.model;

/**
 * One entry of Drive's change feed. {@code outOfScope} marks a file that still exists but no longer belongs to the
 * scope being synced (for a personal drive backing up owned files only: the user does not own it); it carries no
 * file.
 */
public record DriveChange(String fileId, boolean removed, StoredFile file, boolean outOfScope) {

	public DriveChange(String fileId, boolean removed, StoredFile file) {
		this(fileId, removed, file, false);
	}

	public static DriveChange outOfScope(String fileId) {
		return new DriveChange(fileId, false, null, true);
	}
}
