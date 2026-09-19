package org.nm.gdrive_backup.domain.model;

/** Outcome of a full run; {@code archive} and {@code pageToken} are null when the run was cancelled. */
public record InitialSyncResult(DriveScope scope, int fileCount, String pageToken, Archive archive, boolean cancelled) {
}
