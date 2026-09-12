package org.nm.gdrive_backup.adapter.out.google;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.WorkspaceUser;

@EnabledIfSystemProperty(named = "google.service-account.integration", matches = "true")
class GoogleWorkspaceUserDirectoryAdapterIT {

	private static final String KEY_ENV = "GOOGLE_SERVICE_ACCOUNT_KEY";
	private static final String USER_ENV = "GOOGLE_IMPERSONATED_USER";

	@Test
	void listsWorkspaceUsersForTheImpersonatedAdmin() throws Exception {
		String keyPath = requiredEnvironment(KEY_ENV);
		String userEmail = requiredEnvironment(USER_ENV);

		GoogleServiceAccountAdapter credentialAdapter = new GoogleServiceAccountAdapter(Path.of(keyPath));
		ServiceAccountAccess access = credentialAdapter.authenticateAs(userEmail);
		List<WorkspaceUser> users = new GoogleWorkspaceUserDirectoryAdapter(credentialAdapter).listUsers(access);

		assertNotNull(users);
		assertFalse(users.isEmpty());
		assertTrue(users.stream().anyMatch(user -> user.email().equalsIgnoreCase(userEmail))
				|| users.stream().anyMatch(user -> user.email().endsWith("@" + userEmail.split("@", 2)[1])));
	}

	private static String requiredEnvironment(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("Set " + name + " before running this integration test");
		}
		return value;
	}
}
