package org.nm.gdrive_backup.domain.model;

public record WorkspaceUser(String email, String displayName) {

	public WorkspaceUser {
		if (email == null || email.isBlank()) {
			throw new IllegalArgumentException("email must not be blank");
		}
		displayName = displayName == null ? "" : displayName;
	}
}
