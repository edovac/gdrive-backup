package org.nm.gdrive_backup.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

		try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
			var statement = connection.createStatement();
			ResultSet tables = statement.executeQuery(
					"SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name IN "
							+ "('users', 'drives', 'files', 'file_versions', 'file_events', 'sync_state')")) {
			assertEquals(6, tables.getInt(1));
		}
	}
}