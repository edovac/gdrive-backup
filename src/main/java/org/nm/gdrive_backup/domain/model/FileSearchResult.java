package org.nm.gdrive_backup.domain.model;

/** A backed-up file found by name, with its drive labelled like the archive folders ("My Drive (email)"). */
public record FileSearchResult(StoredFile file, String driveLabel) {
}
