package org.nm.gdrive_backup.domain.model;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record GoogleLoginSession(
		UUID sessionId,
		Instant expiresAt,
		Set<String> scopes,
		String userEmail) {

	public GoogleLoginSession {
		if (sessionId == null) {
			throw new IllegalArgumentException("sessionId must not be null");
		}
		if (expiresAt == null) {
			throw new IllegalArgumentException("expiresAt must not be null");
		}
		if (scopes == null || scopes.isEmpty()) {
			throw new IllegalArgumentException("scopes must not be empty");
		}
		if (userEmail == null || userEmail.isBlank()) {
			throw new IllegalArgumentException("userEmail must not be blank");
		}
		scopes = Set.copyOf(scopes);
	}

	public boolean isExpired(Instant now) {
		return !expiresAt.isAfter(now);
	}
}
