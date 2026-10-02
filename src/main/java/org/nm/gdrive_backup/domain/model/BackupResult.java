package org.nm.gdrive_backup.domain.model;

import java.util.List;

/**
 * The outcome of synchronizing one Drive scope. A first synchronization is an
 * initial inventory; later ones consume the Drive changes feed. A scope that
 * threw is reported with a {@code failureMessage} and nothing committed. A scope that finished but could not
 * download some files lists them in {@code skippedFiles}; they are also kept in the persistent report.
 */
public record BackupResult(DriveScope scope, int processedItemCount, boolean initialSync, boolean cancelled,
		String failureMessage, List<DownloadFailure> skippedFiles) {

	public BackupResult(DriveScope scope, int processedItemCount, boolean initialSync, boolean cancelled) {
		this(scope, processedItemCount, initialSync, cancelled, null, List.of());
	}

	public BackupResult(DriveScope scope, int processedItemCount, boolean initialSync, boolean cancelled,
			String failureMessage) {
		this(scope, processedItemCount, initialSync, cancelled, failureMessage, List.of());
	}

	public static BackupResult failed(DriveScope scope, String failureMessage) {
		return new BackupResult(scope, 0, false, false, failureMessage);
	}

	public boolean failed() {
		return failureMessage != null;
	}
}
