package org.nm.gdrive_backup.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nm.gdrive_backup.domain.model.BackupLocations;
import org.nm.gdrive_backup.domain.model.LocationStatus;
import org.nm.gdrive_backup.domain.model.LocationValidation;
import org.nm.gdrive_backup.domain.model.SyncState;

class LocalBackupLocationAdapterTest {

	@TempDir
	Path temporaryDirectory;

	private SqliteDatabase database;
	private LocalVersionStorageAdapter storage;
	private LocalBackupLocationAdapter adapter;

	@BeforeEach
	void createAdapter() throws IOException {
		Path activeDirectory = Files.createDirectories(temporaryDirectory.resolve("active"));
		database = new SqliteDatabase(activeDirectory.resolve("backup.db"));
		database.initialize();
		storage = new LocalVersionStorageAdapter(activeDirectory.resolve("backupRoot"));
		adapter = new LocalBackupLocationAdapter(database, storage);
	}

	@Test
	void reportsActiveLocations() {
		assertEquals(new BackupLocations(
				temporaryDirectory.resolve("active/backupRoot").toAbsolutePath().normalize(),
				temporaryDirectory.resolve("active/backup.db").toAbsolutePath().normalize()),
				adapter.activeLocations());
	}

	@Test
	void missingDestinationIsNewAndIsNotCreatedByTheCheck() {
		assertEquals(LocationStatus.NEW,
				adapter.checkBackupDestination(temporaryDirectory.resolve("backups/nested")).status());
		assertFalse(Files.exists(temporaryDirectory.resolve("backups")));
	}

	@Test
	void emptyDestinationIsNew() throws IOException {
		Path destination = Files.createDirectory(temporaryDirectory.resolve("empty"));

		assertEquals(LocationStatus.NEW, adapter.checkBackupDestination(destination).status());
	}

	@Test
	void destinationWithFilesIsExisting() throws IOException {
		Path destination = Files.createDirectory(temporaryDirectory.resolve("used"));
		Files.writeString(destination.resolve("previous.txt"), "backup");

		assertEquals(LocationStatus.EXISTING, adapter.checkBackupDestination(destination).status());
	}

	@Test
	void regularFileIsNotAValidDestination() throws IOException {
		Path file = Files.writeString(temporaryDirectory.resolve("file.txt"), "not a directory");

		assertEquals(LocationStatus.INVALID, adapter.checkBackupDestination(file).status());
	}

	@Test
	void readOnlyDestinationIsInvalid() throws IOException {
		assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
		Path destination = Files.createDirectory(temporaryDirectory.resolve("read-only"));
		Files.setPosixFilePermissions(destination, PosixFilePermissions.fromString("r-xr-xr-x"));
		try {
			assumeFalse(Files.isWritable(destination), "file permissions are not enforced for this user");

			assertEquals(LocationStatus.INVALID, adapter.checkBackupDestination(destination).status());
		} finally {
			Files.setPosixFilePermissions(destination, PosixFilePermissions.fromString("rwxr-xr-x"));
		}
	}

	@Test
	void missingDatabaseIsNewAndIsNotCreatedByTheCheck() {
		assertEquals(LocationStatus.NEW,
				adapter.checkDatabaseFile(temporaryDirectory.resolve("other/history.db")).status());
		assertFalse(Files.exists(temporaryDirectory.resolve("other")));
	}

	@Test
	void databaseWithBackupHistoryIsExistingWithCounts() throws Exception {
		Path databaseFile = temporaryDirectory.resolve("history.db");
		new SqliteDatabase(databaseFile).initialize();
		try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
			var statement = connection.createStatement()) {
			statement.executeUpdate("INSERT INTO users(email, display_name) "
					+ "VALUES ('a@example.com', 'A'), ('b@example.com', 'B')");
			statement.executeUpdate("INSERT INTO files(file_id, owner_scope, name, parents, mime_type) "
					+ "VALUES ('file-1', 'a@example.com', 'Report', '[]', 'application/pdf')");
		}

		LocationValidation validation = adapter.checkDatabaseFile(databaseFile);

		assertEquals(LocationStatus.EXISTING, validation.status());
		assertEquals("2 users, 1 file", validation.detail());
	}

	@Test
	void fileThatIsNotSqliteIsInvalid() throws IOException {
		Path file = Files.writeString(temporaryDirectory.resolve("notes.db"), "plain text");

		assertEquals(LocationStatus.INVALID, adapter.checkDatabaseFile(file).status());
	}

	@Test
	void directoryIsNotAValidDatabaseFile() {
		assertEquals(LocationStatus.INVALID, adapter.checkDatabaseFile(temporaryDirectory).status());
	}

	@Test
	void applyingDatabaseFileRedirectsPersistence() throws Exception {
		Path databaseFile = temporaryDirectory.resolve("other/history.db");

		adapter.applyDatabaseFile(databaseFile);
		new SqliteSyncStateAdapter(database).save(new SyncState("user@example.com", "token-1"));

		assertEquals(databaseFile.toAbsolutePath().normalize(), adapter.activeLocations().databaseFile());
		try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
			var statement = connection.createStatement();
			ResultSet result = statement.executeQuery(
					"SELECT page_token FROM sync_state WHERE scope_key = 'user@example.com'")) {
			assertTrue(result.next());
			assertEquals("token-1", result.getString(1));
		}
	}

	@Test
	void applyingBackupDestinationRedirectsStoredVersions() throws Exception {
		Path destination = temporaryDirectory.resolve("other-backups");

		adapter.applyBackupDestination(destination);
		Path stored = storage.store("user@example.com", "file-1", "revision-1", "Report.pdf",
				new ByteArrayInputStream(new byte[] { 1 }));

		assertTrue(stored.startsWith(destination.toAbsolutePath().normalize()));
	}
}
