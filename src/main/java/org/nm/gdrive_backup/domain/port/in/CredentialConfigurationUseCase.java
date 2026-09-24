package org.nm.gdrive_backup.domain.port.in;

import java.nio.file.Path;

import org.nm.gdrive_backup.domain.model.CredentialConfiguration;
import org.nm.gdrive_backup.domain.model.CredentialValidation;

/** Lets the admin inspect and change the Google credentials the app uses. */
public interface CredentialConfigurationUseCase {

	CredentialConfiguration currentConfiguration();

	CredentialValidation validateServiceAccountKey(Path file);

	void importServiceAccountKey(Path file);

	void clearServiceAccountKey();

	CredentialValidation validateOAuthClientSecrets(Path file);

	void importOAuthClientSecrets(Path file);

	void clearOAuthClientSecrets();

	void updateProjectId(String projectId);
}
