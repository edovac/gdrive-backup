package org.nm.gdrive_backup.domain.model;

/** What is at the database location: nothing, a database that opens and passes its checks, or one that does not. */
public enum DatabaseStatus {
	MISSING,
	USABLE,
	UNUSABLE
}
