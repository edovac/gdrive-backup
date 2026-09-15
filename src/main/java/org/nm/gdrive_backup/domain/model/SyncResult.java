package org.nm.gdrive_backup.domain.model;

public record SyncResult(DriveScope scope, int changeCount, String pageToken) {
}
