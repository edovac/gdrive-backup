package org.nm.gdrive_backup.domain.model;

import java.time.Instant;

public record StoredDrive(String driveId, String name, Instant lastSyncedAt) {
}