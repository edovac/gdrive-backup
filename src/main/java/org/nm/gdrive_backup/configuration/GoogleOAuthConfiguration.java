package org.nm.gdrive_backup.configuration;

import com.google.api.client.http.javanet.NetHttpTransport;
import org.nm.gdrive_backup.adapter.out.google.GoogleOAuthClientAdapter;
import org.nm.gdrive_backup.adapter.out.google.GoogleOAuthException;
import org.nm.gdrive_backup.domain.port.in.GoogleLoginUseCase;
import org.nm.gdrive_backup.domain.port.in.GoogleAuthorizationApproval;
import org.nm.gdrive_backup.domain.port.out.GoogleOAuthPort;
import org.nm.gdrive_backup.domain.service.GoogleLoginService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class GoogleOAuthConfiguration {

	@Bean
	GoogleOAuthPort googleOAuthPort(@Value("${google.oauth.client-secrets:}") String clientSecretsPath) {
		if (clientSecretsPath == null || clientSecretsPath.isBlank()) {
			return new UnconfiguredGoogleOAuthPort();
		}
		try {
			Reader clientSecrets = Files.newBufferedReader(Path.of(clientSecretsPath), StandardCharsets.UTF_8);
			return new GoogleOAuthClientAdapter(new NetHttpTransport(), clientSecrets);
		} catch (IOException | RuntimeException exception) {
			throw new GoogleOAuthException("Unable to load Google OAuth client secrets", exception);
		}
	}

	@Bean
	GoogleLoginUseCase googleLoginUseCase(GoogleOAuthPort googleOAuthPort) {
		return new GoogleLoginService(googleOAuthPort);
	}

	private static final class UnconfiguredGoogleOAuthPort implements GoogleOAuthPort {

		@Override
		public org.nm.gdrive_backup.domain.model.GoogleLoginSession authenticate(GoogleAuthorizationApproval approval) {
			throw new GoogleOAuthException(
					"Google OAuth is not configured. Set GOOGLE_OAUTH_CLIENT_SECRETS to a client-secrets JSON path.");
		}

		@Override
		public void revoke(org.nm.gdrive_backup.domain.model.GoogleLoginSession session) {
		}
	}
}