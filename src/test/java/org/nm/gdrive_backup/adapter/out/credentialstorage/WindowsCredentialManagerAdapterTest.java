package org.nm.gdrive_backup.adapter.out.credentialstorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.microsoft.credentialstorage.SecretStore;
import com.microsoft.credentialstorage.model.StoredCredential;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowsCredentialManagerAdapterTest {

	@TempDir
	Path tempDir;

	@Test
	void roundTripsAValueUnderTheChunkLimit() {
		FakeSecretStore store = new FakeSecretStore();
		WindowsCredentialManagerAdapter adapter = new WindowsCredentialManagerAdapter(store);

		adapter.updateProjectId("my-cloud-project");

		assertEquals(Optional.of("my-cloud-project"), adapter.projectId());
		assertEquals(1, store.chunkCount("gdrive-backup/project-id"));
	}

	@Test
	void roundTripsAValueThatSpansSeveralChunks() {
		FakeSecretStore store = new FakeSecretStore();
		WindowsCredentialManagerAdapter adapter = new WindowsCredentialManagerAdapter(store);
		String content = "x".repeat(2500);

		adapter.updateProjectId(content);

		assertEquals(3, store.chunkCount("gdrive-backup/project-id"));
		assertEquals(Optional.of(content), adapter.projectId());
	}

	@Test
	void importsAndReadsBackAServiceAccountKeyFile() throws Exception {
		FakeSecretStore store = new FakeSecretStore();
		WindowsCredentialManagerAdapter adapter = new WindowsCredentialManagerAdapter(store);
		Path file = tempDir.resolve("key.json");
		Files.writeString(file, "{\"type\":\"service_account\"}".repeat(80));

		adapter.importServiceAccountKeyFile(file);

		assertEquals(Files.readString(file), adapter.serviceAccountKeyJson().orElseThrow());
		assertTrue(adapter.currentConfiguration().serviceAccountKeyConfigured());
	}

	@Test
	void aFailedChunkWriteRollsBackTheChunksAlreadyWrittenAndLeavesNoManifest() {
		FakeSecretStore store = new FakeSecretStore();
		store.failOnCall(2);
		WindowsCredentialManagerAdapter adapter = new WindowsCredentialManagerAdapter(store);
		String content = "x".repeat(2500);

		assertThrows(WindowsCredentialStorageException.class, () -> adapter.updateProjectId(content));

		assertEquals(0, store.chunkCount("gdrive-backup/project-id"));
		assertFalse(store.contains("gdrive-backup/project-id/manifest"));
		assertFalse(store.contains("gdrive-backup/project-id/chunk-0"));
	}

	@Test
	void aFailedManifestWriteRollsBackAllChunksItJustWrote() {
		FakeSecretStore store = new FakeSecretStore();
		store.failOnCall(3);
		WindowsCredentialManagerAdapter adapter = new WindowsCredentialManagerAdapter(store);
		String content = "x".repeat(1500);

		assertThrows(WindowsCredentialStorageException.class, () -> adapter.updateProjectId(content));

		assertFalse(store.contains("gdrive-backup/project-id/manifest"));
		assertFalse(store.contains("gdrive-backup/project-id/chunk-0"));
		assertFalse(store.contains("gdrive-backup/project-id/chunk-1"));
	}

	@Test
	void reimportingAShorterValueDeletesTheNowOrphanedTrailingChunks() {
		FakeSecretStore store = new FakeSecretStore();
		WindowsCredentialManagerAdapter adapter = new WindowsCredentialManagerAdapter(store);
		adapter.updateProjectId("x".repeat(2500));

		adapter.updateProjectId("short");

		assertEquals(Optional.of("short"), adapter.projectId());
		assertEquals(1, store.chunkCount("gdrive-backup/project-id"));
		assertFalse(store.contains("gdrive-backup/project-id/chunk-1"));
		assertFalse(store.contains("gdrive-backup/project-id/chunk-2"));
	}

	@Test
	void clearDeletesTheManifestAndEveryChunk() throws Exception {
		FakeSecretStore store = new FakeSecretStore();
		WindowsCredentialManagerAdapter adapter = new WindowsCredentialManagerAdapter(store);
		Path file = tempDir.resolve("key.json");
		Files.writeString(file, "{\"type\":\"service_account\"}".repeat(80));
		adapter.importServiceAccountKeyFile(file);
		assertEquals(3, store.chunkCount("gdrive-backup/service-account-key"));

		adapter.clearServiceAccountKey();

		assertEquals(Optional.empty(), adapter.serviceAccountKeyJson());
		assertFalse(store.contains("gdrive-backup/service-account-key/manifest"));
		assertFalse(store.contains("gdrive-backup/service-account-key/chunk-0"));
		assertFalse(store.contains("gdrive-backup/service-account-key/chunk-1"));
		assertFalse(store.contains("gdrive-backup/service-account-key/chunk-2"));
	}

	@Test
	void aMissingChunkIsTreatedAsAnAbsentCredential() {
		FakeSecretStore store = new FakeSecretStore();
		WindowsCredentialManagerAdapter adapter = new WindowsCredentialManagerAdapter(store);
		adapter.updateProjectId("x".repeat(2500));
		store.delete("gdrive-backup/project-id/chunk-1");

		assertEquals(Optional.empty(), adapter.projectId());
	}

	@Test
	void nothingStoredIsReportedAsAbsent() {
		WindowsCredentialManagerAdapter adapter = new WindowsCredentialManagerAdapter(new FakeSecretStore());

		assertEquals(Optional.empty(), adapter.serviceAccountKeyJson());
		assertEquals(Optional.empty(), adapter.oauthClientSecretsJson());
		assertEquals(Optional.empty(), adapter.projectId());
	}

	/** In-memory fake standing in for the native Credential Manager store, with an optional forced failure. */
	private static final class FakeSecretStore implements SecretStore<StoredCredential> {

		private final Map<String, StoredCredential> entries = new HashMap<>();
		private int callCount;
		private int failOnCall = -1;

		void failOnCall(int callNumber) {
			this.failOnCall = callNumber;
		}

		boolean contains(String key) {
			return entries.containsKey(key);
		}

		int chunkCount(String baseKey) {
			int count = 0;
			while (entries.containsKey(baseKey + "/chunk-" + count)) {
				count++;
			}
			return count;
		}

		@Override
		public StoredCredential get(String key) {
			return entries.get(key);
		}

		@Override
		public boolean delete(String key) {
			return entries.remove(key) != null;
		}

		@Override
		public boolean add(String key, StoredCredential secret) {
			callCount++;
			if (callCount == failOnCall) {
				return false;
			}
			entries.put(key, secret);
			return true;
		}

		@Override
		public boolean isSecure() {
			return true;
		}
	}
}
