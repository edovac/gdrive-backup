package org.nm.gdrive_backup.domain.model;

/** Outcome of an incremental run; {@code archive} is null when nothing changed or the run was cancelled. */
public record SyncResult(DriveScope scope, int changeCount, String pageToken, Archive archive, boolean cancelled) {
}
