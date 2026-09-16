package org.nm.gdrive_backup.domain.model;

import java.util.List;

public record SyncResult(
		DriveScope scope,
		int changeCount,
		String fromPageToken,
		String toPageToken,
		List<FileEvent> events,
		List<FileCapture> capturedContent) {
}
