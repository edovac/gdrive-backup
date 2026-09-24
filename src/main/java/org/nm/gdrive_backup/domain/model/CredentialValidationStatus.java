package org.nm.gdrive_backup.domain.model;

/** The outcome of checking a candidate credential file. */
public enum CredentialValidationStatus {

	/** The file is a usable credential and can be imported. */
	VALID,

	/** The file cannot be used. */
	INVALID
}
