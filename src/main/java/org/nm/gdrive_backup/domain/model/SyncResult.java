package org.nm.gdrive_backup.domain.model;

public record SyncResult(String scopeKey, int changeCount, String pageToken) {
}