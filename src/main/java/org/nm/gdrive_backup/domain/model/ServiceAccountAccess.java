package org.nm.gdrive_backup.domain.model;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record ServiceAccountAccess(
		UUID accessId,
		String impersonatedUserEmail,
		Instant expiresAt,
		Set<String> scopes) {

	public ServiceAccountAccess {
		if (accessId == null || impersonatedUserEmail == null || impersonatedUserEmail.isBlank()) {
			throw new IllegalArgumentException("access identity must be present");
		}
		if (expiresAt == null || scopes == null || scopes.isEmpty()) {
			throw new IllegalArgumentException("expiration and scopes must be present");
		}
		scopes = Set.copyOf(scopes);
	}
}