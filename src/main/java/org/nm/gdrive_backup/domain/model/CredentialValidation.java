package org.nm.gdrive_backup.domain.model;

/**
 * The result of checking a candidate credential file. {@code detail} explains an invalid file
 * or briefly describes a valid one.
 */
public record CredentialValidation(CredentialValidationStatus status, String detail) {
}
