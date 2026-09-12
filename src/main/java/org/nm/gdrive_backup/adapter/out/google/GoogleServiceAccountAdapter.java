package org.nm.gdrive_backup.adapter.out.google;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.port.out.ServiceAccountCredentialPort;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GoogleServiceAccountAdapter implements ServiceAccountCredentialPort {

	public static final String DRIVE_READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly";
	public static final String DIRECTORY_USER_READONLY_SCOPE =
			"https://www.googleapis.com/auth/admin.directory.user.readonly";
	public static final String REPORTS_USAGE_READONLY_SCOPE =
				"https://www.googleapis.com/auth/admin.reports.usage.readonly";
	public static final String CLOUD_PLATFORM_SCOPE =
				"https://www.googleapis.com/auth/cloud-platform";

	private static final Set<String> SCOPES = Set.of(
			DRIVE_READONLY_SCOPE,
			DIRECTORY_USER_READONLY_SCOPE);

	private final ServiceAccountCredentials serviceAccountCredentials;
	private final Map<UUID, GoogleCredentials> credentials = new ConcurrentHashMap<>();

	public GoogleServiceAccountAdapter(Path keyPath) throws IOException {
		try (InputStream key = Files.newInputStream(keyPath)) {
			this.serviceAccountCredentials = ServiceAccountCredentials.fromStream(key);
		}
	}

	@Override
	public ServiceAccountAccess authenticateAs(String userEmail) {
		try {
			ServiceAccountCredentials scopedCredentials = (ServiceAccountCredentials) serviceAccountCredentials
					.createScoped(SCOPES);
			GoogleCredentials delegatedCredentials = scopedCredentials.createDelegated(userEmail);
			delegatedCredentials.refreshIfExpired();
			AccessToken accessToken = delegatedCredentials.getAccessToken();
			if (accessToken == null || accessToken.getExpirationTime() == null) {
				throw new GoogleOAuthException("Google service-account access token has no expiration");
			}
			UUID accessId = UUID.randomUUID();
			credentials.put(accessId, delegatedCredentials);
			return new ServiceAccountAccess(accessId, userEmail,
					Instant.ofEpochMilli(accessToken.getExpirationTime().getTime()), SCOPES);
		} catch (IOException | RuntimeException exception) {
			throw new GoogleOAuthException("Google service-account authentication failed", exception);
		}
	}

	GoogleCredentials credentialsFor(ServiceAccountAccess access) {
		GoogleCredentials credential = credentials.get(access.accessId());
		if (credential == null) {
			throw new GoogleOAuthException("Unknown or expired service-account access");
		}
		try {
			credential.refreshIfExpired();
			return credential;
		} catch (IOException exception) {
			throw new GoogleOAuthException("Unable to refresh service-account access", exception);
		}
	}

	GoogleCredentials reportsCredentialsFor(ServiceAccountAccess access) {
		if (access == null) {
			throw new IllegalArgumentException("access must not be null");
		}
		try {
			ServiceAccountCredentials scopedCredentials = (ServiceAccountCredentials) serviceAccountCredentials
					.createScoped(Set.of(REPORTS_USAGE_READONLY_SCOPE));
			GoogleCredentials delegatedCredentials = scopedCredentials.createDelegated(access.impersonatedUserEmail());
			delegatedCredentials.refreshIfExpired();
			return delegatedCredentials;
		} catch (IOException | RuntimeException exception) {
			throw new GoogleOAuthException("Unable to create Workspace Reports access", exception);
		}
	}

	GoogleCredentials cloudCredentials() {
		try {
			ServiceAccountCredentials scopedCredentials = (ServiceAccountCredentials) serviceAccountCredentials
					.createScoped(Set.of(CLOUD_PLATFORM_SCOPE));
			scopedCredentials.refreshIfExpired();
			return scopedCredentials;
		} catch (IOException | RuntimeException exception) {
			throw new GoogleOAuthException("Unable to create Cloud project access", exception);
		}
	}
}