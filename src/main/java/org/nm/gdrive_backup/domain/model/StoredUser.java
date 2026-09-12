package org.nm.gdrive_backup.domain.model;

import java.time.Instant;

public record StoredUser(String email, String displayName, Instant lastSyncedAt) {
}