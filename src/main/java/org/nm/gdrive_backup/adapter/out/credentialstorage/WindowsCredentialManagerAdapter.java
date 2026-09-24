package org.nm.gdrive_backup.adapter.out.credentialstorage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.microsoft.credentialstorage.SecretStore;
import com.microsoft.credentialstorage.StorageProvider;
import com.microsoft.credentialstorage.StorageProvider.SecureOption;
import com.microsoft.credentialstorage.model.StoredCredential;

import org.nm.gdrive_backup.domain.model.CredentialConfiguration;
import org.nm.gdrive_backup.domain.model.CredentialValidation;
import org.nm.gdrive_backup.domain.port.out.CredentialStoragePort;

/**
 * Stores credentials in Windows Credential Manager. Each of the three credential fields (service
 * account key, OAuth client secrets, project id) is stored under its own target-name prefix as a
 * chain of {@code <base>/chunk-N} entries plus a {@code <base>/manifest} entry holding the chunk
 * count, because a single Credential Manager blob is far smaller than a service-account key JSON
 * file. Chunks are written before the manifest and read after it, so a manifest is only ever
 * visible once every chunk it names has been written; a failed write rolls back whatever chunks
 * it already wrote so a stale credential is never left live under an old manifest.
 */
public class WindowsCredentialManagerAdapter implements CredentialStoragePort {

	private static final String CREDENTIAL_USERNAME = "gdrive-backup";
	private static final int CHUNK_CHAR_LIMIT = 1000;
	private static final String DEFAULT_KEY_PREFIX = "gdrive-backup";

	private final SecretStore<StoredCredential> store;
	private final String serviceAccountKeyBase;
	private final String oauthClientSecretsBase;
	private final String projectIdBase;

	public WindowsCredentialManagerAdapter() {
		this(StorageProvider.getCredentialStorage(true, SecureOption.REQUIRED), DEFAULT_KEY_PREFIX);
	}

	WindowsCredentialManagerAdapter(SecretStore<StoredCredential> store) {
		this(store, DEFAULT_KEY_PREFIX);
	}

	/**
	 * {@code keyPrefix} lets tests that exercise a real OS credential store (the Windows IT) use a
	 * namespace distinct from {@value #DEFAULT_KEY_PREFIX}, the one the packaged app uses, so a test
	 * run never reads, overwrites or clears an admin's real configured credentials.
	 */
	WindowsCredentialManagerAdapter(SecretStore<StoredCredential> store, String keyPrefix) {
		this.store = store;
		this.serviceAccountKeyBase = keyPrefix + "/service-account-key";
		this.oauthClientSecretsBase = keyPrefix + "/oauth-client-secrets";
		this.projectIdBase = keyPrefix + "/project-id";
	}

	@Override
	public CredentialConfiguration currentConfiguration() {
		return new CredentialConfiguration(
				serviceAccountKeyJson().isPresent(), oauthClientSecretsJson().isPresent(), projectId().orElse(null));
	}

	@Override
	public CredentialValidation validateServiceAccountKeyFile(Path file) {
		return CredentialFileValidation.checkServiceAccountKey(file);
	}

	@Override
	public void importServiceAccountKeyFile(Path file) {
		writeChunked(serviceAccountKeyBase, readString(file));
	}

	@Override
	public void clearServiceAccountKey() {
		deleteChunked(serviceAccountKeyBase);
	}

	@Override
	public CredentialValidation validateOAuthClientSecretsFile(Path file) {
		return CredentialFileValidation.checkOAuthClientSecrets(file);
	}

	@Override
	public void importOAuthClientSecretsFile(Path file) {
		writeChunked(oauthClientSecretsBase, readString(file));
	}

	@Override
	public void clearOAuthClientSecrets() {
		deleteChunked(oauthClientSecretsBase);
	}

	@Override
	public void updateProjectId(String projectId) {
		writeChunked(projectIdBase, projectId);
	}

	@Override
	public Optional<String> serviceAccountKeyJson() {
		return readChunked(serviceAccountKeyBase);
	}

	@Override
	public Optional<String> oauthClientSecretsJson() {
		return readChunked(oauthClientSecretsBase);
	}

	@Override
	public Optional<String> projectId() {
		return readChunked(projectIdBase);
	}

	private void writeChunked(String baseKey, String content) {
		int previousChunkCount = manifestChunkCount(store.get(manifestKey(baseKey)));
		List<String> chunks = splitIntoChunks(content);
		for (int i = 0; i < chunks.size(); i++) {
			if (!store.add(chunkKey(baseKey, i), new StoredCredential(CREDENTIAL_USERNAME, chunks.get(i).toCharArray()))) {
				rollbackChunks(baseKey, i);
				throw new WindowsCredentialStorageException("Unable to write credential chunk " + i + " for " + baseKey);
			}
		}
		String count = Integer.toString(chunks.size());
		if (!store.add(manifestKey(baseKey), new StoredCredential(CREDENTIAL_USERNAME, count.toCharArray()))) {
			rollbackChunks(baseKey, chunks.size());
			throw new WindowsCredentialStorageException("Unable to write credential manifest for " + baseKey);
		}
		// The new manifest is committed, so any trailing chunk from a longer previous value is an
		// orphan no reader will look at again; delete it rather than leaving old secret bytes behind.
		for (int i = chunks.size(); i < previousChunkCount; i++) {
			store.delete(chunkKey(baseKey, i));
		}
	}

	private Optional<String> readChunked(String baseKey) {
		StoredCredential manifest = store.get(manifestKey(baseKey));
		if (manifest == null) {
			return Optional.empty();
		}
		int count;
		try {
			count = Integer.parseInt(new String(manifest.getPassword()));
		} catch (NumberFormatException exception) {
			return Optional.empty();
		}
		StringBuilder content = new StringBuilder();
		for (int i = 0; i < count; i++) {
			StoredCredential chunk = store.get(chunkKey(baseKey, i));
			if (chunk == null) {
				return Optional.empty();
			}
			content.append(chunk.getPassword());
		}
		return Optional.of(content.toString());
	}

	private void deleteChunked(String baseKey) {
		StoredCredential manifest = store.get(manifestKey(baseKey));
		int count = manifestChunkCount(manifest);
		store.delete(manifestKey(baseKey));
		for (int i = 0; i < count; i++) {
			store.delete(chunkKey(baseKey, i));
		}
	}

	private void rollbackChunks(String baseKey, int writtenCount) {
		for (int i = 0; i < writtenCount; i++) {
			store.delete(chunkKey(baseKey, i));
		}
	}

	private static int manifestChunkCount(StoredCredential manifest) {
		if (manifest == null) {
			return 0;
		}
		try {
			return Integer.parseInt(new String(manifest.getPassword()));
		} catch (NumberFormatException exception) {
			return 0;
		}
	}

	private static List<String> splitIntoChunks(String content) {
		List<String> chunks = new ArrayList<>();
		for (int start = 0; start < content.length(); start += CHUNK_CHAR_LIMIT) {
			chunks.add(content.substring(start, Math.min(start + CHUNK_CHAR_LIMIT, content.length())));
		}
		return chunks;
	}

	private static String chunkKey(String baseKey, int index) {
		return baseKey + "/chunk-" + index;
	}

	private static String manifestKey(String baseKey) {
		return baseKey + "/manifest";
	}

	private static String readString(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException exception) {
			throw new IllegalArgumentException("Unable to read " + file + ": " + exception.getMessage(), exception);
		}
	}
}
