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
import java.util.concurrent.atomic.AtomicReference;

public class SqliteDatabase {

	private static final String SCHEMA_RESOURCE = "/db/schema.sql";

	private final AtomicReference<Path> databasePath;

	public SqliteDatabase(Path databasePath) {
		this.databasePath = new AtomicReference<>(databasePath);
	}

	public Path path() {
		return databasePath.get();
	}

	Connection openConnection() throws SQLException {
		Connection connection = DriverManager.getConnection(jdbcUrl(databasePath.get()));
		try (Statement statement = connection.createStatement()) {
			// Both settings are per connection, so every connection needs them.
			statement.execute("PRAGMA foreign_keys = ON");
			statement.execute("PRAGMA busy_timeout = 5000");
		} catch (SQLException exception) {
			connection.close();
			throw exception;
		}
		return connection;
	}

	public void initialize() {
		initialize(databasePath.get());
	}

	/**
	 * Initializes the schema in another database file, then routes every later connection
	 * to it. The current database stays active if the new one cannot be initialized.
	 */
	public void switchTo(Path newDatabasePath) {
		try {
			Path parent = newDatabasePath.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to create SQLite database directory", exception);
		}
		initialize(newDatabasePath);
		databasePath.set(newDatabasePath);
	}

	/** Routes later connections to {@code path} without touching the file, for swapping databases that are already set up. */
	void redirect(Path path) {
		databasePath.set(path);
	}

	private static void initialize(Path path) {
		try (Connection connection = DriverManager.getConnection(jdbcUrl(path));
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

	private static String jdbcUrl(Path path) {
		return "jdbc:sqlite:" + path;
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
