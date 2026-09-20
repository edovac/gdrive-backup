package org.nm.gdrive_backup.domain.model;

/**
 * The outcome of synchronizing one Drive scope. A first synchronization is an
 * initial inventory; later ones consume the Drive changes feed. A scope that
 * threw is reported with a {@code failureMessage} and nothing committed.
 */
public record BackupResult(DriveScope scope, int processedItemCount, boolean initialSync, boolean cancelled,
		String failureMessage) {

	public BackupResult(DriveScope scope, int processedItemCount, boolean initialSync, boolean cancelled) {
		this(scope, processedItemCount, initialSync, cancelled, null);
	}

	public static BackupResult failed(DriveScope scope, String failureMessage) {
		return new BackupResult(scope, 0, false, false, failureMessage);
	}

	public boolean failed() {
		return failureMessage != null;
	}
}
