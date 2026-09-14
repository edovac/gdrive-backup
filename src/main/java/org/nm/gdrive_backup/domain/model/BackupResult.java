package org.nm.gdrive_backup.domain.model;

/**
 * The outcome of synchronizing one Drive scope. A first synchronization is an
 * initial inventory; later ones consume the Drive changes feed.
 */
public record BackupResult(String scopeKey, int processedItemCount, boolean initialSync, boolean cancelled) {
}
