package org.nm.gdrive_backup.adapter.out.google;

import com.google.api.client.http.javanet.NetHttpTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nm.gdrive_backup.adapter.out.credentialstorage.InMemoryCredentialStorageAdapter;
import org.nm.gdrive_backup.domain.model.GoogleLoginSession;
import org.nm.gdrive_backup.domain.port.out.CredentialStoragePort;
import org.nm.gdrive_backup.domain.service.GoogleLoginService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.awt.Desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "google.oauth.integration", matches = "true")
class GoogleOAuthClientAdapterIT {

	private static final String DRIVE_READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly";
	private static final String EMAIL_SCOPE = "https://www.googleapis.com/auth/userinfo.email";
	private static final String OPENID_SCOPE = "openid";
	private static final String CLIENT_SECRETS_ENV = "GOOGLE_OAUTH_CLIENT_SECRETS";

	@Test
	void authenticatesAndRevokesAgainstGoogle() throws IOException {
		Path clientSecretsPath = clientSecretsPath();
		CredentialStoragePort credentialStoragePort = new InMemoryCredentialStorageAdapter();
		credentialStoragePort.importOAuthClientSecretsFile(clientSecretsPath);
		GoogleOAuthClientAdapter adapter = new GoogleOAuthClientAdapter(
				new NetHttpTransport(), credentialStoragePort);
		GoogleLoginService loginService = new GoogleLoginService(adapter);

		GoogleLoginSession session = loginService.login(authorizationUri -> {
			if (!Desktop.isDesktopSupported()) {
				return false;
			}
			try {
				Desktop.getDesktop().browse(authorizationUri);
				return true;
			} catch (IOException exception) {
				return false;
			}
		});

		assertNotNull(session.sessionId());
		assertFalse(session.isExpired(Instant.now()));
		assertEquals(3, session.scopes().size());
		assertTrue(session.scopes().contains(DRIVE_READONLY_SCOPE));
		assertTrue(session.scopes().contains(OPENID_SCOPE));
		assertTrue(session.scopes().contains(EMAIL_SCOPE));
		assertNotNull(session.userEmail());
		assertFalse(session.userEmail().isBlank());

		loginService.logout(session);
	}

	private static Path clientSecretsPath() {
		String configuredPath = System.getenv(CLIENT_SECRETS_ENV);
		assertNotNull(configuredPath,
				"Set " + CLIENT_SECRETS_ENV + " to the downloaded Google OAuth client-secrets JSON path");
		Path path = Path.of(configuredPath);
		assertTrue(Files.isRegularFile(path), "OAuth client-secrets file does not exist: " + path);
		return path;
	}
}
