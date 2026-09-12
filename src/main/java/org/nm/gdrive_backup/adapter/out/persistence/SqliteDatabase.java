package org.nm.gdrive_backup.adapter.out.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class SqliteDatabase {

	private static final String SCHEMA_RESOURCE = "/db/schema.sql";

	private final String jdbcUrl;

	public SqliteDatabase(Path databasePath) {
		this.jdbcUrl = "jdbc:sqlite:" + databasePath;
	}

	Connection openConnection() throws SQLException {
		return DriverManager.getConnection(jdbcUrl);
	}

	public void initialize() {
		try (Connection connection = openConnection();
			Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA foreign_keys = ON");
			for (String schemaStatement : readSchema().split(";")) {
				if (!schemaStatement.isBlank()) {
					statement.execute(schemaStatement);
				}
			}
		} catch (SQLException | IOException exception) {
			throw new IllegalStateException("Unable to initialize SQLite database", exception);
		}
	}

	private static String readSchema() throws IOException {
		try (InputStream stream = SqliteDatabase.class.getResourceAsStream(SCHEMA_RESOURCE)) {
			if (stream == null) {
				throw new IOException("Missing SQLite schema resource: " + SCHEMA_RESOURCE);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}