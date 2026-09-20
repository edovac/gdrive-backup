package org.nm.gdrive_backup.domain.model;

/** An archive a deletion would remove; {@code sizeBytes} is null when its file is already missing. */
public record ObsoleteArchive(long id, int sequenceNumber, String path, Long sizeBytes) {
}
