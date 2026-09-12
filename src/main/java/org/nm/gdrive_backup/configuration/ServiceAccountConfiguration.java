package org.nm.gdrive_backup.configuration;

import org.nm.gdrive_backup.adapter.out.google.GoogleOAuthException;
import org.nm.gdrive_backup.adapter.out.google.GoogleDriveAdapter;
import org.nm.gdrive_backup.adapter.out.google.GoogleServiceAccountAdapter;
import org.nm.gdrive_backup.adapter.out.google.GoogleWorkspaceUserDirectoryAdapter;
import org.nm.gdrive_backup.domain.port.out.DriveReadPort;
import org.nm.gdrive_backup.domain.port.out.WorkspaceUserDirectoryPort;
import org.nm.gdrive_backup.domain.port.in.ServiceAccountAuthenticationUseCase;
import org.nm.gdrive_backup.domain.port.in.WorkspaceUserListingUseCase;
import org.nm.gdrive_backup.domain.port.out.ServiceAccountCredentialPort;
import org.nm.gdrive_backup.domain.service.ServiceAccountAuthenticationService;
import org.nm.gdrive_backup.domain.service.WorkspaceUserListingService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;

import java.io.IOException;
import java.nio.file.Path;

@Configuration
public class ServiceAccountConfiguration {

	@Bean
	@ConditionalOnExpression("'${google.service-account.key:}'.trim().length() > 0")
	GoogleServiceAccountAdapter googleServiceAccountAdapter(ServiceAccountProperties properties) {
		String keyPath = properties.key();
		try {
			return new GoogleServiceAccountAdapter(Path.of(keyPath));
		} catch (IOException | RuntimeException exception) {
			throw new GoogleOAuthException("Unable to load Google service-account key", exception);
		}
	}

	@Bean
	@Primary
	ServiceAccountCredentialPort serviceAccountCredentialPort(
			ObjectProvider<GoogleServiceAccountAdapter> adapterProvider) {
		GoogleServiceAccountAdapter adapter = adapterProvider.getIfAvailable();
		if (adapter != null) {
			return adapter;
		}
		return userEmail -> {
			throw new GoogleOAuthException(
					"Google service-account authentication is not configured. "
							+ "Set GOOGLE_SERVICE_ACCOUNT_KEY to a service-account JSON path.");
		};
	}

	@Bean
	@ConditionalOnExpression("'${google.service-account.key:}'.trim().length() > 0")
	DriveReadPort driveReadPort(@Qualifier("googleServiceAccountAdapter") GoogleServiceAccountAdapter adapter) {
		return new GoogleDriveAdapter(adapter);
	}

	@Bean
	@ConditionalOnExpression("'${google.service-account.key:}'.trim().length() > 0")
	WorkspaceUserDirectoryPort workspaceUserDirectoryPort(
			@Qualifier("googleServiceAccountAdapter") GoogleServiceAccountAdapter adapter) {
		return new GoogleWorkspaceUserDirectoryAdapter(adapter);
	}

	@Bean
	@Primary
	WorkspaceUserDirectoryPort workspaceUserDirectoryPortFallback() {
		return access -> {
			throw new GoogleOAuthException(
					"Google Workspace user listing is not configured. "
						+ "Set GOOGLE_SERVICE_ACCOUNT_KEY to a service-account JSON path.");
		};
	}

	@Bean
	ServiceAccountAuthenticationUseCase serviceAccountAuthenticationUseCase(
			ServiceAccountCredentialPort credentialPort) {
		return new ServiceAccountAuthenticationService(credentialPort);
	}

	@Bean
	WorkspaceUserListingUseCase workspaceUserListingUseCase(
			WorkspaceUserDirectoryPort workspaceUserDirectoryPort) {
		return new WorkspaceUserListingService(workspaceUserDirectoryPort);
	}
}