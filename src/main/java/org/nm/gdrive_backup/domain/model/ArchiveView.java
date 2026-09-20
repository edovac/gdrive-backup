package org.nm.gdrive_backup.domain.model;

/** One archive as the Archive manager shows it; {@code sizeBytes} is null when the file is missing. */
public record ArchiveView(Archive archive, ArchiveState state, boolean fileMissing, Long sizeBytes) {
}
