package org.nm.gdrive_backup.domain.model;

/** A file whose captured bytes exist only in an obsolete archive and will be gone once it is deleted. */
public record LostContent(String fileId, String name, String revisionId, String reason) {
}
