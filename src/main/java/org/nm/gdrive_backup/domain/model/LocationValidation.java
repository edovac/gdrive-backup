package org.nm.gdrive_backup.domain.model;

/**
 * The result of checking a location. {@code detail} explains an invalid location or
 * summarizes what an existing one already contains.
 */
public record LocationValidation(LocationStatus status, String detail) {
}
