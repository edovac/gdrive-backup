package org.nm.gdrive_backup.domain.service;

import java.nio.file.Path;

import org.nm.gdrive_backup.domain.model.CredentialConfiguration;
import org.nm.gdrive_backup.domain.model.CredentialValidation;
import org.nm.gdrive_backup.domain.model.CredentialValidationStatus;
import org.nm.gdrive_backup.domain.port.in.CredentialConfigurationUseCase;
import org.nm.gdrive_backup.domain.port.out.CredentialStoragePort;

/** Validates and applies changes to the Google credentials the app uses. */
public class CredentialConfigurationService implements CredentialConfigurationUseCase {

	private final CredentialStoragePort storagePort;
	private final BackupActivity backupActivity;

	public CredentialConfigurationService(CredentialStoragePort storagePort, BackupActivity backupActivity) {
		this.storagePort = storagePort;
		this.backupActivity = backupActivity;
	}

	@Override
	public CredentialConfiguration currentConfiguration() {
		return storagePort.currentConfiguration();
	}

	@Override
	public CredentialValidation validateServiceAccountKey(Path file) {
		return storagePort.validateServiceAccountKeyFile(normalize(file));
	}

	@Override
	public void importServiceAccountKey(Path file) {
		Path target = normalize(file);
		backupActivity.changeLocations(() -> {
			requireValid(storagePort.validateServiceAccountKeyFile(target));
			storagePort.importServiceAccountKeyFile(target);
		});
	}

	@Override
	public void clearServiceAccountKey() {
		backupActivity.changeLocations(storagePort::clearServiceAccountKey);
	}

	@Override
	public CredentialValidation validateOAuthClientSecrets(Path file) {
		return storagePort.validateOAuthClientSecretsFile(normalize(file));
	}

	@Override
	public void importOAuthClientSecrets(Path file) {
		Path target = normalize(file);
		backupActivity.changeLocations(() -> {
			requireValid(storagePort.validateOAuthClientSecretsFile(target));
			storagePort.importOAuthClientSecretsFile(target);
		});
	}

	@Override
	public void clearOAuthClientSecrets() {
		backupActivity.changeLocations(storagePort::clearOAuthClientSecrets);
	}

	@Override
	public void updateProjectId(String projectId) {
		if (projectId == null || projectId.isBlank()) {
			throw new IllegalArgumentException("A project id is required");
		}
		String target = projectId.trim();
		backupActivity.changeLocations(() -> storagePort.updateProjectId(target));
	}

	private static void requireValid(CredentialValidation validation) {
		if (validation.status() == CredentialValidationStatus.INVALID) {
			throw new IllegalArgumentException(validation.detail());
		}
	}

	private static Path normalize(Path path) {
		if (path == null) {
			throw new IllegalArgumentException("A file is required");
		}
		return path.toAbsolutePath().normalize();
	}
}
