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

import org.nm.gdrive_backup.domain.model.BackupLocation;
import org.nm.gdrive_backup.domain.model.LocationStatus;
import org.nm.gdrive_backup.domain.model.LocationValidation;
import org.nm.gdrive_backup.domain.port.out.BackupLocationPort;
import org.sqlite.SQLiteConfig;

/** Checks and switches the single local root folder for the backup history database and the capture store. */
public class LocalBackupLocationAdapter implements BackupLocationPort {

	private static final String DATABASE_FILE_NAME = "backup.db";
	private static final byte[] SQLITE_HEADER = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
	private static final Set<String> HISTORY_TABLES = Set.of("users", "files");

	private final SqliteDatabase database;
	private final LocalCaptureStorageAdapter storage;

	public LocalBackupLocationAdapter(SqliteDatabase database, LocalCaptureStorageAdapter storage) {
		this.database = database;
		this.storage = storage;
	}

	@Override
	public BackupLocation activeLocation() {
		return new BackupLocation(normalize(storage.root()));
	}

	@Override
	public LocationValidation checkRoot(Path root) {
		Path target = normalize(root);
		if (!Files.exists(target)) {
			return checkCreatable(target, "The directory and a new backup history will be created");
		}
		if (!Files.isDirectory(target)) {
			return invalid("Not a directory: " + target);
		}
		if (!canCreateFileIn(target)) {
			return invalid("Directory is not writable: " + target);
		}
		Path databaseFile = target.resolve(DATABASE_FILE_NAME);
		if (Files.exists(databaseFile)) {
			return describeDatabaseFile(databaseFile);
		}
		try (Stream<Path> entries = Files.list(target)) {
			return entries.findAny().isPresent()
					? new LocationValidation(LocationStatus.EXISTING,
							"The directory already contains files; a new backup history will be created")
					: new LocationValidation(LocationStatus.NEW, "Empty directory; a new backup history will be created");
		} catch (IOException exception) {
			return invalid("Unable to read directory " + target + ": " + exception.getMessage());
		}
	}

	@Override
	public void applyRoot(Path root) {
		Path target = normalize(root);
		storage.switchTo(target);
		database.switchTo(target.resolve(DATABASE_FILE_NAME));
	}

	private static LocationValidation describeDatabaseFile(Path databaseFile) {
		if (!Files.isRegularFile(databaseFile)) {
			return invalid("Not a file: " + databaseFile);
		}
		try {
			if (Files.size(databaseFile) == 0) {
				return new LocationValidation(LocationStatus.NEW, "Empty backup history; the backup tables will be created");
			}
			if (!hasSqliteHeader(databaseFile)) {
				return invalid("Not an SQLite database: " + databaseFile);
			}
		} catch (IOException exception) {
			return invalid("Unable to read " + databaseFile + ": " + exception.getMessage());
		}
		return describeHistory(databaseFile);
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
