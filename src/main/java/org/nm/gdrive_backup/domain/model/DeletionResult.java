package org.nm.gdrive_backup.domain.model;

import java.util.List;

/** {@code filesThatCouldNotBeDeleted} are archive files the database no longer references but that remain on disk. */
public record DeletionResult(int deletedFiles, long freedBytes, List<String> filesThatCouldNotBeDeleted) {
}
