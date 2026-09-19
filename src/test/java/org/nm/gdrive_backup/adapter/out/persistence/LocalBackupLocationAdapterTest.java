package org.nm.gdrive_backup.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import org.nm.gdrive_backup.domain.model.BackupLocation;
import org.nm.gdrive_backup.domain.model.LocationStatus;
import org.nm.gdrive_backup.domain.model.LocationValidation;
import org.nm.gdrive_backup.domain.model.SyncState;

class LocalBackupLocationAdapterTest {

	@TempDir
	Path temporaryDirectory;

	private SqliteDatabase database;
	private LocalBackupRoot storage;
	private LocalBackupLocationAdapter adapter;

	@BeforeEach
	void createAdapter() throws IOException {
		Path activeDirectory = Files.createDirectories(temporaryDirectory.resolve("active"));
		database = new SqliteDatabase(activeDirectory.resolve("backup.db"));
		database.initialize();
		storage = new LocalBackupRoot(activeDirectory);
		adapter = new LocalBackupLocationAdapter(database, storage);
	}

	@Test
	void reportsActiveLocation() {
		assertEquals(new BackupLocation(temporaryDirectory.resolve("active").toAbsolutePath().normalize()),
				adapter.activeLocation());
	}

	@Test
	void missingRootIsNewAndIsNotCreatedByTheCheck() {
		assertEquals(LocationStatus.NEW,
				adapter.checkRoot(temporaryDirectory.resolve("backups/nested")).status());
		assertFalse(Files.exists(temporaryDirectory.resolve("backups")));
	}

	@Test
	void emptyRootIsNew() throws IOException {
		Path root = Files.createDirectory(temporaryDirectory.resolve("empty"));

		assertEquals(LocationStatus.NEW, adapter.checkRoot(root).status());
	}

	@Test
	void rootWithOtherFilesButNoDatabaseIsExisting() throws IOException {
		Path root = Files.createDirectory(temporaryDirectory.resolve("used"));
		Files.writeString(root.resolve("previous.txt"), "backup");

		assertEquals(LocationStatus.EXISTING, adapter.checkRoot(root).status());
	}

	@Test
	void regularFileIsNotAValidRoot() throws IOException {
		Path file = Files.writeString(temporaryDirectory.resolve("file.txt"), "not a directory");

		assertEquals(LocationStatus.INVALID, adapter.checkRoot(file).status());
	}

	@Test
	void readOnlyRootIsInvalid() throws IOException {
		assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
		Path root = Files.createDirectory(temporaryDirectory.resolve("read-only"));
		Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("r-xr-xr-x"));
		try {
			assumeFalse(Files.isWritable(root), "file permissions are not enforced for this user");

			assertEquals(LocationStatus.INVALID, adapter.checkRoot(root).status());
		} finally {
			Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
		}
	}

	@Test
	void rootWithBackupHistoryIsExistingWithCounts() throws Exception {
		Path root = Files.createDirectory(temporaryDirectory.resolve("history"));
		Path databaseFile = root.resolve("backup.db");
		new SqliteDatabase(databaseFile).initialize();
		try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
			var statement = connection.createStatement()) {
			statement.executeUpdate("INSERT INTO users(email, display_name) "
					+ "VALUES ('a@example.com', 'A'), ('b@example.com', 'B')");
			statement.executeUpdate("INSERT INTO files(file_id, owner_scope, name, parents, mime_type) "
					+ "VALUES ('file-1', 'a@example.com', 'Report', '[]', 'application/pdf')");
		}

		LocationValidation validation = adapter.checkRoot(root);

		assertEquals(LocationStatus.EXISTING, validation.status());
		assertEquals("2 users, 1 file", validation.detail());
	}

	@Test
	void rootWithEmptyDatabaseFileIsNew() throws IOException {
		Path root = Files.createDirectory(temporaryDirectory.resolve("fresh"));
		Files.createFile(root.resolve("backup.db"));

		assertEquals(LocationStatus.NEW, adapter.checkRoot(root).status());
	}

	@Test
	void rootWithNonSqliteDatabaseFileIsInvalid() throws IOException {
		Path root = Files.createDirectory(temporaryDirectory.resolve("notes"));
		Files.writeString(root.resolve("backup.db"), "plain text");

		assertEquals(LocationStatus.INVALID, adapter.checkRoot(root).status());
	}

	@Test
	void applyingRootRedirectsPersistenceAndArchives() throws Exception {
		Path root = temporaryDirectory.resolve("other");

		adapter.applyRoot(root);
		new SqliteSyncStateAdapter(database).save(new SyncState("user@example.com", "token-1"));

		Path normalizedRoot = root.toAbsolutePath().normalize();
		assertEquals(normalizedRoot, adapter.activeLocation().root());
		assertTrue(Files.exists(normalizedRoot.resolve("backup.db")));
		assertEquals(normalizedRoot, storage.root());
		try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + normalizedRoot.resolve("backup.db"));
			var statement = connection.createStatement();
			ResultSet result = statement.executeQuery(
					"SELECT page_token FROM sync_state WHERE scope_key = 'user@example.com'")) {
			assertTrue(result.next());
			assertEquals("token-1", result.getString(1));
		}
	}
}
