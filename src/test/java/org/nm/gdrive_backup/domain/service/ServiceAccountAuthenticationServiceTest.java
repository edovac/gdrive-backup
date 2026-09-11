package org.nm.gdrive_backup.domain.service;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.port.out.ServiceAccountCredentialPort;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServiceAccountAuthenticationServiceTest {

	@Test
	void delegatesAuthenticationForAWorkspaceUser() {
		ServiceAccountAccess expected = new ServiceAccountAccess(UUID.randomUUID(), "user@example.com",
				Instant.now().plusSeconds(300), Set.of("scope"));
		ServiceAccountCredentialPort port = userEmail -> expected;

		ServiceAccountAccess actual = new ServiceAccountAuthenticationService(port)
				.authenticateAs("user@example.com");

		assertSame(expected, actual);
	}

	@Test
	void rejectsBlankWorkspaceUser() {
		ServiceAccountAuthenticationService service = new ServiceAccountAuthenticationService(userEmail -> null);

		assertThrows(IllegalArgumentException.class, () -> service.authenticateAs(" "));
	}
}