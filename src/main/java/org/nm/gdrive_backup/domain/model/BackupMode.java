package org.nm.gdrive_backup.domain.model;

/** Which synchronization flow the admin has chosen for a backup run. */
public enum BackupMode {

	/** Inventory and back up the entire scope, ignoring any saved change cursor. */
	FULL,

	/**
	 * Back up only what changed since the last run. Falls back to a full inventory when
	 * no baseline exists yet or the saved change cursor has expired.
	 */
	INCREMENTAL
}
