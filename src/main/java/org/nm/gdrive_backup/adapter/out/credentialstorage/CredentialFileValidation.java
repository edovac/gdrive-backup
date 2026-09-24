package org.nm.gdrive_backup.adapter.out.credentialstorage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.json.gson.GsonFactory;
import com.google.auth.oauth2.ServiceAccountCredentials;

import org.nm.gdrive_backup.domain.model.CredentialValidation;
import org.nm.gdrive_backup.domain.model.CredentialValidationStatus;

/**
 * Parses a candidate credential file with the same Google libraries used to build real
 * credentials from it, so "valid" here means "usable at sign-in/sync time," not just "is JSON."
 * Shared by every {@code CredentialStoragePort} adapter.
 */
final class CredentialFileValidation {

	private CredentialFileValidation() {
	}

	static CredentialValidation checkServiceAccountKey(Path file) {
		try {
			ServiceAccountCredentials.fromStream(new ByteArrayInputStream(Files.readAllBytes(file)));
			return new CredentialValidation(CredentialValidationStatus.VALID, "Valid service-account key");
		} catch (IOException | RuntimeException exception) {
			return invalid(file, exception);
		}
	}

	static CredentialValidation checkOAuthClientSecrets(Path file) {
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			GoogleClientSecrets.load(GsonFactory.getDefaultInstance(), reader);
			return new CredentialValidation(CredentialValidationStatus.VALID, "Valid OAuth client secrets");
		} catch (IOException | RuntimeException exception) {
			return invalid(file, exception);
		}
	}

	private static CredentialValidation invalid(Path file, Exception exception) {
		return new CredentialValidation(CredentialValidationStatus.INVALID,
				"Not a usable credential file " + file + ": " + exception.getMessage());
	}
}
