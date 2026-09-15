package org.nm.gdrive_backup.domain.model;

public record InitialSyncResult(DriveScope scope, int fileCount, String pageToken) {
}
