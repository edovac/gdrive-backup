package org.nm.gdrive_backup.configuration;

import org.nm.gdrive_backup.adapter.out.credentialstorage.InMemoryCredentialStorageAdapter;
import org.nm.gdrive_backup.adapter.out.credentialstorage.WindowsCredentialManagerAdapter;
import org.nm.gdrive_backup.domain.port.in.CredentialConfigurationUseCase;
import org.nm.gdrive_backup.domain.port.out.CredentialStoragePort;
import org.nm.gdrive_backup.domain.service.BackupActivity;
import org.nm.gdrive_backup.domain.service.CredentialConfigurationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CredentialStorageConfiguration {

	@Bean
	CredentialStoragePort credentialStoragePort() {
		return isWindows() ? new WindowsCredentialManagerAdapter() : new InMemoryCredentialStorageAdapter();
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
	}

	@Bean
	CredentialConfigurationUseCase credentialConfigurationUseCase(
			CredentialStoragePort credentialStoragePort, BackupActivity backupActivity) {
		return new CredentialConfigurationService(credentialStoragePort, backupActivity);
	}
}
