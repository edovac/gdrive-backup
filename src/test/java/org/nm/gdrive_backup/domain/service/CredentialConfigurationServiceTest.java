package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.adapter.out.credentialstorage.InMemoryCredentialStorageAdapter;
import org.nm.gdrive_backup.domain.model.CredentialConfiguration;
import org.nm.gdrive_backup.domain.model.CredentialValidation;
import org.nm.gdrive_backup.domain.model.CredentialValidationStatus;
import org.nm.gdrive_backup.domain.port.out.CredentialStoragePort;

class CredentialConfigurationServiceTest {

	private static final Path FILE = Path.of("key.json").toAbsolutePath().normalize();

	@Test
	void reportsNothingConfiguredInitially() {
		CredentialConfiguration configuration = new CredentialConfigurationService(
				new InMemoryCredentialStorageAdapter(), new BackupActivity()).currentConfiguration();

		assertFalse(configuration.serviceAccountKeyConfigured());
		assertFalse(configuration.oauthClientSecretsConfigured());
	}

	@Test
	void rejectsAnInvalidServiceAccountKeyWithoutImportingIt() {
		CredentialStoragePort port = mock(CredentialStoragePort.class);
		when(port.validateServiceAccountKeyFile(FILE))
				.thenReturn(new CredentialValidation(CredentialValidationStatus.INVALID, "Not a valid key"));

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> new CredentialConfigurationService(port, new BackupActivity()).importServiceAccountKey(FILE));

		assertEquals("Not a valid key", error.getMessage());
		verify(port, never()).importServiceAccountKeyFile(any());
	}

	@Test
	void importsAValidServiceAccountKey() {
		CredentialStoragePort port = mock(CredentialStoragePort.class);
		when(port.validateServiceAccountKeyFile(FILE))
				.thenReturn(new CredentialValidation(CredentialValidationStatus.VALID, "Valid service-account key"));

		new CredentialConfigurationService(port, new BackupActivity()).importServiceAccountKey(FILE);

		verify(port).importServiceAccountKeyFile(FILE);
	}

	@Test
	void clearingRemovesAPreviouslyImportedKey() {
		InMemoryCredentialStorageAdapter port = new InMemoryCredentialStorageAdapter();
		CredentialConfigurationService service = new CredentialConfigurationService(port, new BackupActivity());
		port.updateProjectId("still-here");

		service.clearServiceAccountKey();

		assertFalse(service.currentConfiguration().serviceAccountKeyConfigured());
		assertEquals("still-here", service.currentConfiguration().projectId());
	}

	@Test
	void rejectsABlankProjectId() {
		CredentialConfigurationService service = new CredentialConfigurationService(
				new InMemoryCredentialStorageAdapter(), new BackupActivity());

		assertThrows(IllegalArgumentException.class, () -> service.updateProjectId("  "));
	}

	@Test
	void updatesTheProjectId() {
		CredentialConfigurationService service = new CredentialConfigurationService(
				new InMemoryCredentialStorageAdapter(), new BackupActivity());

		service.updateProjectId(" my-project ");

		assertEquals("my-project", service.currentConfiguration().projectId());
	}

	@Test
	void refusesCredentialChangesWhileABackupRuns() throws Exception {
		CredentialStoragePort port = mock(CredentialStoragePort.class);
		BackupActivity activity = new BackupActivity();
		CountDownLatch backupStarted = new CountDownLatch(1);
		CountDownLatch finishBackup = new CountDownLatch(1);
		Thread backup = new Thread(() -> activity.duringBackup(() -> {
			backupStarted.countDown();
			awaitQuietly(finishBackup);
			return null;
		}));
		backup.start();
		backupStarted.await();

		try {
			assertThrows(IllegalStateException.class,
					() -> new CredentialConfigurationService(port, activity).updateProjectId("my-project"));
			verify(port, never()).updateProjectId(any());
		} finally {
			finishBackup.countDown();
			backup.join();
		}
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}
}
