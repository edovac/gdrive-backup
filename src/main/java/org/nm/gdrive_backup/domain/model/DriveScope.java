package org.nm.gdrive_backup.domain.model;

/** A user's My Drive or a Shared Drive, identified by {@code key} (an email or a {@code drive_id}). */
public record DriveScope(String key, DriveScopeType type) {

	public static DriveScope personal(String userEmail) {
		return new DriveScope(userEmail, DriveScopeType.PERSONAL);
	}

	public static DriveScope sharedDrive(String driveId) {
		return new DriveScope(driveId, DriveScopeType.SHARED_DRIVE);
	}
}
