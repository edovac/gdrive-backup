package org.nm.gdrive_backup.domain.model;

/** Which Google credentials are currently configured, and the Cloud project id in use. */
public record CredentialConfiguration(
		boolean serviceAccountKeyConfigured,
		boolean oauthClientSecretsConfigured,
		String projectId) {
}
