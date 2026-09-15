package org.nm.gdrive_backup.domain.model;

import java.nio.file.Path;

/** Where a captured file's content was written, relative to the capture store's root, and its size. */
public record StoredCapture(Path relativePath, long sizeBytes) {
}
