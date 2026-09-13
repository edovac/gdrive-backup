package org.nm.gdrive_backup.domain.model;

/** The outcome of checking a candidate backup destination or history database. */
public enum LocationStatus {

	/** The location is already the active one. */
	UNCHANGED,

	/** The location is usable and holds no backup data yet. */
	NEW,

	/** The location is usable and already holds backup files or backup history. */
	EXISTING,

	/** The location cannot be used. */
	INVALID
}
