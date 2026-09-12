package org.nm.gdrive_backup.domain.model;

public record InitialSyncResult(String scopeKey, int fileCount, String pageToken) {
}