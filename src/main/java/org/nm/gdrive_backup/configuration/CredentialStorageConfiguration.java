package org.nm.gdrive_backup.configuration;

import org.nm.gdrive_backup.adapter.out.credentialstorage.InMemoryCredentialStorageAdapter;
import org.nm.gdrive_backup.domain.port.in.CredentialConfigurationUseCase;
import org.nm.gdrive_backup.domain.port.out.CredentialStoragePort;
import org.nm.gdrive_backup.domain.service.BackupActivity;
import org.nm.gdrive_backup.domain.service.CredentialConfigurationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CredentialStorageConfiguration {

	// TODO(stage 4): pick a WindowsCredentialManagerAdapter when running on Windows.
	@Bean
	CredentialStoragePort credentialStoragePort() {
		return new InMemoryCredentialStorageAdapter();
	}

	@Bean
	CredentialConfigurationUseCase credentialConfigurationUseCase(
			CredentialStoragePort credentialStoragePort, BackupActivity backupActivity) {
		return new CredentialConfigurationService(credentialStoragePort, backupActivity);
	}
}
