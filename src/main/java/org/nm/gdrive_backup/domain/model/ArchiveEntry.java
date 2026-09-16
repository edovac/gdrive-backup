package org.nm.gdrive_backup.domain.model;

/** One file to place inside an archive: {@code entryName} is its path within the zip; {@code sourceRelativePath} is where its bytes already live, relative to the backup root. */
public record ArchiveEntry(String entryName, String sourceRelativePath) {
}
