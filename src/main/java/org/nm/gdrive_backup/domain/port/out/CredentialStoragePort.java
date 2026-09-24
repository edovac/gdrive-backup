package org.nm.gdrive_backup.domain.port.out;

import java.nio.file.Path;
import java.util.Optional;

import org.nm.gdrive_backup.domain.model.CredentialConfiguration;
import org.nm.gdrive_backup.domain.model.CredentialValidation;

/**
 * Checks and stores the Google credentials the app needs: the service-account key, the OAuth
 * client secrets, and the Cloud project id. Checking must not store or change anything; only the
 * {@code import}/{@code clear}/{@code update} methods change what is stored. The stored JSON
 * content is only ever exposed to callers that build Google SDK credentials from it, at the
 * point of use, never to the UI.
 */
public interface CredentialStoragePort {

	CredentialConfiguration currentConfiguration();

	CredentialValidation validateServiceAccountKeyFile(Path file);

	void importServiceAccountKeyFile(Path file);

	void clearServiceAccountKey();

	CredentialValidation validateOAuthClientSecretsFile(Path file);

	void importOAuthClientSecretsFile(Path file);

	void clearOAuthClientSecrets();

	void updateProjectId(String projectId);

	Optional<String> serviceAccountKeyJson();

	Optional<String> oauthClientSecretsJson();

	Optional<String> projectId();
}
