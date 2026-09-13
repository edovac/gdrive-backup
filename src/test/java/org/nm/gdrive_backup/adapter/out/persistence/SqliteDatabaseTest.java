package org.nm.gdrive_backup.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteDatabaseTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void initializesBackupSchema() throws Exception {
		Path databasePath = temporaryDirectory.resolve("backup.db");
		new SqliteDatabase(databasePath).initialize();

		assertEquals(6, backupTableCount(databasePath));
	}

	@Test
	void switchInitializesTheNewDatabaseAndRoutesConnectionsToIt() throws Exception {
		SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("first.db"));
		database.initialize();
		Path secondPath = temporaryDirectory.resolve("nested/second.db");

		database.switchTo(secondPath);

		assertEquals(secondPath, database.path());
		assertEquals(6, backupTableCount(secondPath));
		try (Connection connection = database.openConnection();
			var statement = connection.createStatement()) {
			statement.executeUpdate("INSERT INTO sync_state(scope_key, page_token) VALUES ('scope', 'token')");
		}
		try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + secondPath);
			var statement = connection.createStatement();
			ResultSet rows = statement.executeQuery("SELECT count(*) FROM sync_state")) {
			assertEquals(1, rows.getInt(1));
		}
	}

	@Test
	void failedSwitchKeepsTheCurrentDatabase() throws Exception {
		Path firstPath = temporaryDirectory.resolve("first.db");
		SqliteDatabase database = new SqliteDatabase(firstPath);
		database.initialize();
		Path blockingFile = Files.writeString(temporaryDirectory.resolve("not-a-directory"), "file");

		assertThrows(IllegalStateException.class, () -> database.switchTo(blockingFile.resolve("second.db")));

		assertEquals(firstPath, database.path());
	}

	private static int backupTableCount(Path databasePath) throws Exception {
		try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
			var statement = connection.createStatement();
			ResultSet tables = statement.executeQuery(
					"SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name IN "
							+ "('users', 'drives', 'files', 'file_versions', 'file_events', 'sync_state')")) {
			return tables.getInt(1);
		}
	}
}
