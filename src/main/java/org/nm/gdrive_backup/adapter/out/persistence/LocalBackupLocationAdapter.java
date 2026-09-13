package org.nm.gdrive_backup.adapter.out.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import org.nm.gdrive_backup.domain.model.BackupLocations;
import org.nm.gdrive_backup.domain.model.LocationStatus;
import org.nm.gdrive_backup.domain.model.LocationValidation;
import org.nm.gdrive_backup.domain.port.out.BackupLocationPort;
import org.sqlite.SQLiteConfig;

/** Checks and switches the local backup destination and the SQLite backup history database. */
public class LocalBackupLocationAdapter implements BackupLocationPort {

	private static final byte[] SQLITE_HEADER = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
	private static final Set<String> HISTORY_TABLES = Set.of("users", "files");

	private final SqliteDatabase database;
	private final LocalVersionStorageAdapter storage;

	public LocalBackupLocationAdapter(SqliteDatabase database, LocalVersionStorageAdapter storage) {
		this.database = database;
		this.storage = storage;
	}

	@Override
	public BackupLocations activeLocations() {
		return new BackupLocations(normalize(storage.root()), normalize(database.path()));
	}

	@Override
	public LocationValidation checkBackupDestination(Path destination) {
		Path target = normalize(destination);
		if (!Files.exists(target)) {
			return checkCreatable(target, "The directory will be created");
		}
		if (!Files.isDirectory(target)) {
			return invalid("Not a directory: " + target);
		}
		if (!canCreateFileIn(target)) {
			return invalid("Directory is not writable: " + target);
		}
		try (Stream<Path> entries = Files.list(target)) {
			return entries.findAny().isPresent()
					? new LocationValidation(LocationStatus.EXISTING, "The directory already contains files")
					: new LocationValidation(LocationStatus.NEW, "Empty directory");
		} catch (IOException exception) {
			return invalid("Unable to read directory " + target + ": " + exception.getMessage());
		}
	}

	@Override
	public LocationValidation checkDatabaseFile(Path databaseFile) {
		Path target = normalize(databaseFile);
		if (Files.isDirectory(target)) {
			return invalid("Path is a directory: " + target);
		}
		if (!Files.exists(target)) {
			return checkCreatable(target.getParent(), "A new, empty backup history will be created");
		}
		// SQLite writes journal files next to the database, so its directory must be writable.
		if (!canCreateFileIn(target.getParent())) {
			return invalid("Directory is not writable: " + target.getParent());
		}
		try {
			if (Files.size(target) == 0) {
				return new LocationValidation(LocationStatus.NEW, "Empty file; a new backup history will be created");
			}
			if (!hasSqliteHeader(target)) {
				return invalid("Not an SQLite database: " + target);
			}
		} catch (IOException exception) {
			return invalid("Unable to read " + target + ": " + exception.getMessage());
		}
		return describeHistory(target);
	}

	@Override
	public void applyBackupDestination(Path destination) {
		storage.switchTo(normalize(destination));
	}

	@Override
	public void applyDatabaseFile(Path databaseFile) {
		database.switchTo(normalize(databaseFile));
	}

	private static LocationValidation checkCreatable(Path path, String detail) {
		Path ancestor = path;
		while (ancestor != null && !Files.exists(ancestor)) {
			ancestor = ancestor.getParent();
		}
		if (ancestor == null || !Files.isDirectory(ancestor)) {
			return invalid("Cannot create " + path);
		}
		if (!canCreateFileIn(ancestor)) {
			return invalid("Directory is not writable: " + ancestor);
		}
		return new LocationValidation(LocationStatus.NEW, detail);
	}

	// Files.isWritable is unreliable on Windows, so writability is proven with a temporary file.
	private static boolean canCreateFileIn(Path directory) {
		try {
			Files.delete(Files.createTempFile(directory, ".gdrive-backup-write-check", ".tmp"));
			return true;
		} catch (IOException exception) {
			return false;
		}
	}

	private static boolean hasSqliteHeader(Path file) throws IOException {
		try (InputStream input = Files.newInputStream(file)) {
			return Arrays.equals(input.readNBytes(SQLITE_HEADER.length), SQLITE_HEADER);
		}
	}

	private static LocationValidation describeHistory(Path databaseFile) {
		SQLiteConfig config = new SQLiteConfig();
		config.setReadOnly(true);
		try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile, config.toProperties());
			Statement statement = connection.createStatement()) {
			Set<String> tables = new HashSet<>();
			try (ResultSet result = statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
				while (result.next()) {
					tables.add(result.getString("name"));
				}
			}
			if (!tables.containsAll(HISTORY_TABLES)) {
				return new LocationValidation(LocationStatus.NEW,
						"SQLite database without backup history; the backup tables will be added");
			}
			return new LocationValidation(LocationStatus.EXISTING,
					count(statement, "users", "user") + ", " + count(statement, "files", "file"));
		} catch (SQLException exception) {
			return invalid("Unable to read SQLite database " + databaseFile + ": " + exception.getMessage());
		}
	}

	private static String count(Statement statement, String table, String noun) throws SQLException {
		try (ResultSet result = statement.executeQuery("SELECT count(*) FROM " + table)) {
			long count = result.next() ? result.getLong(1) : 0;
			return String.format("%,d %s%s", count, noun, count == 1 ? "" : "s");
		}
	}

	private static LocationValidation invalid(String detail) {
		return new LocationValidation(LocationStatus.INVALID, detail);
	}

	private static Path normalize(Path path) {
		if (path == null) {
			throw new IllegalArgumentException("A location path is required");
		}
		return path.toAbsolutePath().normalize();
	}
}
