package org.nm.gdrive_backup.domain.model;

/**
 * Which files a personal drive backup takes. Drive lists every file a user can open, including ones other people
 * shared with them and items in Shared Drives; a Shared Drive backup is unaffected by this choice.
 */
public enum PersonalDriveContent {

	/** Only the files the user owns, so each file is backed up once, under its owner. */
	OWNED_ONLY,

	/** Every file the user can access, including those shared with them by others. */
	ALL_ACCESSIBLE
}
