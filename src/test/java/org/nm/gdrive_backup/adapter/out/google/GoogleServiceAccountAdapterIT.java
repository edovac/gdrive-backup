package org.nm.gdrive_backup.adapter.out.google;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "google.service-account.integration", matches = "true")
class GoogleServiceAccountAdapterIT {

	private static final String KEY_ENV = "GOOGLE_SERVICE_ACCOUNT_KEY";
	private static final String USER_ENV = "GOOGLE_IMPERSONATED_USER";

	@Test
	void authenticatesAsAWorkspaceUser() throws Exception {
		String keyPath = requiredEnvironment(KEY_ENV);
		String userEmail = requiredEnvironment(USER_ENV);

		ServiceAccountAccess access = new GoogleServiceAccountAdapter(Path.of(keyPath))
				.authenticateAs(userEmail);

		assertNotNull(access.accessId());
		assertEquals(userEmail, access.impersonatedUserEmail());
		assertFalse(access.scopes().isEmpty());
		assertTrue(access.scopes().contains(GoogleServiceAccountAdapter.DRIVE_READONLY_SCOPE));
		assertTrue(access.scopes().contains(GoogleServiceAccountAdapter.DIRECTORY_USER_READONLY_SCOPE));
	}

	private static String requiredEnvironment(String name) {
		String value = System.getenv(name);
		assertNotNull(value, "Set " + name + " before running this integration test");
		assertFalse(value.isBlank(), name + " must not be blank");
		return value;
	}
}