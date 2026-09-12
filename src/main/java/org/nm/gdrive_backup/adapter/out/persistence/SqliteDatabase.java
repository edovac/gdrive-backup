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

import org.springframework.stereotype.Component;

@Component
public class SqliteDatabase {

	private static final String SCHEMA_RESOURCE = "/db/schema.sql";

	private final String jdbcUrl;

	public SqliteDatabase() {
		this(resolveDatabasePath());
	}

	SqliteDatabase(Path databasePath) {
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

	private static Path resolveDatabasePath() {
		String configuredPath = System.getProperty("gdrive.backup.database");
		Path path = configuredPath == null || configuredPath.isBlank()
				? Path.of(System.getProperty("user.home"), ".gdrive-backup", "backup.db")
				: Path.of(configuredPath);
		try {
			Files.createDirectories(path.toAbsolutePath().getParent());
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to create SQLite database directory", exception);
		}
		return path;
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