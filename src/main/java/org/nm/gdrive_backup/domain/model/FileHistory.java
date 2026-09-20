package org.nm.gdrive_backup.domain.model;

import java.util.List;

/** A file's current metadata and its events and captures merged in chronological order. */
public record FileHistory(StoredFile file, List<HistoryEntry> entries) {
}
