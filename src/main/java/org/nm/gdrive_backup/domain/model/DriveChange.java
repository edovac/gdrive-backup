package org.nm.gdrive_backup.domain.model;

public record DriveChange(String fileId, boolean removed, StoredFile file) {
}