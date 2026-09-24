package org.nm.gdrive_backup.adapter.out.credentialstorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.microsoft.credentialstorage.SecretStore;
import com.microsoft.credentialstorage.StorageProvider;
import com.microsoft.credentialstorage.StorageProvider.SecureOption;
import com.microsoft.credentialstorage.model.StoredCredential;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Exercises the real Windows Credential Manager, so it can only run on Windows and is opt-in via
 * Failsafe's {@code *IT} convention. Uses a {@code gdrive-backup-it/} key prefix, distinct from the
 * {@code gdrive-backup/} prefix the packaged app uses, so a test run never touches a developer's own
 * configured credentials; {@link #cleanUp()} removes whatever this test wrote either way.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsCredentialManagerAdapterIT {

	private static final String KEY_PREFIX = "gdrive-backup-it";

	private final SecretStore<StoredCredential> store = StorageProvider.getCredentialStorage(true, SecureOption.REQUIRED);
	private final WindowsCredentialManagerAdapter adapter = new WindowsCredentialManagerAdapter(store, KEY_PREFIX);

	@AfterEach
	void cleanUp() {
		adapter.clearServiceAccountKey();
		adapter.clearOAuthClientSecrets();
		store.delete(KEY_PREFIX + "/project-id/manifest");
		for (int i = 0; i < 5; i++) {
			store.delete(KEY_PREFIX + "/project-id/chunk-" + i);
		}
	}

	@Test
	void roundTripsAValueUnderOneChunk() {
		adapter.updateProjectId("my-cloud-project-id");

		assertEquals(Optional.of("my-cloud-project-id"), adapter.projectId());
	}

	@Test
	void roundTripsAValueForcingThreeOrMoreChunksByteExact() {
		String content = "abcdefghij".repeat(350);
		assertTrue(content.length() > 3000, "test content should span at least 3 chunks of 1000 chars");

		adapter.importServiceAccountKeyFile(writeTempJsonLikeContent(content));

		assertEquals(content, adapter.serviceAccountKeyJson().orElseThrow());
	}

	@Test
	void clearRemovesTheManifestAndReportsNotConfigured() {
		adapter.updateProjectId("temporary-project-id");
		assertTrue(adapter.projectId().isPresent());

		// updateProjectId has no clear counterpart on the port; drive the manifest-removal path
		// through the service-account key field instead, which does.
		adapter.importServiceAccountKeyFile(writeTempJsonLikeContent("x".repeat(50)));
		assertTrue(adapter.serviceAccountKeyJson().isPresent());

		adapter.clearServiceAccountKey();

		assertEquals(Optional.empty(), adapter.serviceAccountKeyJson());
		assertFalse(adapter.currentConfiguration().serviceAccountKeyConfigured());
	}

	private static Path writeTempJsonLikeContent(String content) {
		try {
			Path file = Files.createTempFile("gdrive-backup-it", ".json");
			file.toFile().deleteOnExit();
			Files.writeString(file, content);
			return file;
		} catch (IOException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
