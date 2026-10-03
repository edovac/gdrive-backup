package org.nm.gdrive_backup.domain.model;

import java.util.List;

/**
 * What a database rebuild did. {@code problems} names each archive that could not be used or was left unlinked;
 * {@code scopesWithoutCursor} lists the drives whose newest archive records no change cursor, so their next
 * incremental backup starts with a full inventory. {@code previousDatabaseBackup} is the file name the replaced
 * database was kept under, or null when there was none.
 */
public record DatabaseRebuildResult(
		boolean cancelled,
		int archivesRestored,
		int drivesRestored,
		List<String> problems,
		List<String> scopesWithoutCursor,
		String previousDatabaseBackup) {

	public static DatabaseRebuildResult cancelledRun() {
		return new DatabaseRebuildResult(true, 0, 0, List.of(), List.of(), null);
	}
}
