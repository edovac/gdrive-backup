package org.nm.gdrive_backup.adapter.out.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import org.nm.gdrive_backup.domain.model.DatabaseStatus;
import org.nm.gdrive_backup.domain.port.out.DatabaseRebuildPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the new database as {@code backup.db.rebuild} next to the current one and only renames it into place when
 * it is complete; the old file is renamed to {@code backup.db.<timestamp>.bak}, never deleted.
 */
public class SqliteDatabaseRebuildAdapter implements DatabaseRebuildPort {

	private static final Logger LOGGER = LoggerFactory.getLogger(SqliteDatabaseRebuildAdapter.class);
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private final SqliteDatabase database;
	private Path current;

	public SqliteDatabaseRebuildAdapter(SqliteDatabase database) {
		this.database = database;
	}

	@Override
	public DatabaseStatus status() {
		Path path = database.path();
		if (!Files.isRegularFile(path)) {
			return DatabaseStatus.MISSING;
		}
		try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
				Statement statement = connection.createStatement();
				ResultSet check = statement.executeQuery("PRAGMA integrity_check")) {
			if (!check.next() || !"ok".equalsIgnoreCase(check.getString(1))) {
				return DatabaseStatus.UNUSABLE;
			}
			try (ResultSet tables = statement.executeQuery(
					"SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name IN ('archives', 'files', 'sync_state')")) {
				return tables.next() && tables.getInt(1) == 3 ? DatabaseStatus.USABLE : DatabaseStatus.UNUSABLE;
			}
		} catch (SQLException exception) {
			LOGGER.error("The database {} cannot be opened or checked", path, exception);
			return DatabaseStatus.UNUSABLE;
		}
	}

	@Override
	public void startFresh() {
		current = database.path();
		Path fresh = sibling(current, ".rebuild");
		try {
			Files.deleteIfExists(fresh);
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to remove an earlier unfinished rebuild " + fresh, exception);
		}
		database.switchTo(fresh);
		LOGGER.info("Building the new database at {}", fresh);
	}

	@Override
	public Optional<String> complete() {
		Path fresh = database.path();
		Path kept = null;
		try {
			if (Files.exists(current)) {
				kept = sibling(current, "." + LocalDateTime.now().format(STAMP) + ".bak");
				Files.move(current, kept);
			}
			Files.move(fresh, current);
		} catch (IOException exception) {
			// The old database goes back where it was, so a failed swap never leaves the root without one.
			try {
				if (kept != null && !Files.exists(current)) {
					Files.move(kept, current);
				}
			} catch (IOException restoreFailure) {
				exception.addSuppressed(restoreFailure);
			}
			LOGGER.error("Unable to swap the rebuilt database {} in for {}", fresh, current, exception);
			throw new IllegalStateException("Unable to put the rebuilt database in place: " + exception.getMessage(),
					exception);
		}
		database.redirect(current);
		LOGGER.info("The rebuilt database is now {}; {}", current,
				kept == null ? "there was no previous database" : "the previous one was kept as " + kept.getFileName());
		return Optional.ofNullable(kept).map(path -> path.getFileName().toString());
	}

	@Override
	public void abort() {
		Path fresh = database.path();
		database.redirect(current);
		if (!fresh.equals(current)) {
			LOGGER.info("Discarding the unfinished rebuilt database {}; {} is unchanged", fresh, current);
			try {
				Files.deleteIfExists(fresh);
			} catch (IOException ignored) {
				// A leftover .rebuild file is replaced by the next attempt.
			}
		}
	}

	private static Path sibling(Path path, String suffix) {
		return path.resolveSibling(path.getFileName() + suffix);
	}
}
