package org.nm.gdrive_backup.adapter.out.credentialstorage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.nm.gdrive_backup.domain.model.CredentialConfiguration;
import org.nm.gdrive_backup.domain.model.CredentialValidation;
import org.nm.gdrive_backup.domain.port.out.CredentialStoragePort;

/**
 * Holds credentials in memory only, for platforms with no secure OS credential store integrated
 * yet (dev machines, CI) and as the fake used by domain-service tests. Never touches disk, and
 * nothing here survives a restart.
 */
public class InMemoryCredentialStorageAdapter implements CredentialStoragePort {

	private volatile String serviceAccountKeyJson;
	private volatile String oauthClientSecretsJson;
	private volatile String projectId;

	@Override
	public CredentialConfiguration currentConfiguration() {
		return new CredentialConfiguration(serviceAccountKeyJson != null, oauthClientSecretsJson != null, projectId);
	}

	@Override
	public CredentialValidation validateServiceAccountKeyFile(Path file) {
		return CredentialFileValidation.checkServiceAccountKey(file);
	}

	@Override
	public void importServiceAccountKeyFile(Path file) {
		serviceAccountKeyJson = readString(file);
	}

	@Override
	public void clearServiceAccountKey() {
		serviceAccountKeyJson = null;
	}

	@Override
	public CredentialValidation validateOAuthClientSecretsFile(Path file) {
		return CredentialFileValidation.checkOAuthClientSecrets(file);
	}

	@Override
	public void importOAuthClientSecretsFile(Path file) {
		oauthClientSecretsJson = readString(file);
	}

	@Override
	public void clearOAuthClientSecrets() {
		oauthClientSecretsJson = null;
	}

	@Override
	public void updateProjectId(String projectId) {
		this.projectId = projectId;
	}

	@Override
	public Optional<String> serviceAccountKeyJson() {
		return Optional.ofNullable(serviceAccountKeyJson);
	}

	@Override
	public Optional<String> oauthClientSecretsJson() {
		return Optional.ofNullable(oauthClientSecretsJson);
	}

	@Override
	public Optional<String> projectId() {
		return Optional.ofNullable(projectId);
	}

	private static String readString(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException exception) {
			throw new IllegalArgumentException("Unable to read " + file + ": " + exception.getMessage(), exception);
		}
	}
}
