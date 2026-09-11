package org.nm.gdrive_backup.configuration;

import org.nm.gdrive_backup.adapter.out.google.GoogleOAuthException;
import org.nm.gdrive_backup.adapter.out.google.GoogleServiceAccountAdapter;
import org.nm.gdrive_backup.domain.port.in.ServiceAccountAuthenticationUseCase;
import org.nm.gdrive_backup.domain.port.out.ServiceAccountCredentialPort;
import org.nm.gdrive_backup.domain.service.ServiceAccountAuthenticationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;

@Configuration
public class ServiceAccountConfiguration {

	@Bean
	ServiceAccountCredentialPort serviceAccountCredentialPort(ServiceAccountProperties properties) {
		String keyPath = properties.key();
		if (keyPath == null || keyPath.isBlank()) {
			return userEmail -> {
				throw new GoogleOAuthException(
						"Google service-account authentication is not configured. "
								+ "Set GOOGLE_SERVICE_ACCOUNT_KEY to a service-account JSON path.");
			};
		}
		try {
			return new GoogleServiceAccountAdapter(Path.of(keyPath));
		} catch (IOException | RuntimeException exception) {
			throw new GoogleOAuthException("Unable to load Google service-account key", exception);
		}
	}

	@Bean
	ServiceAccountAuthenticationUseCase serviceAccountAuthenticationUseCase(
			ServiceAccountCredentialPort credentialPort) {
		return new ServiceAccountAuthenticationService(credentialPort);
	}
}