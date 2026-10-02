package org.nm.gdrive_backup.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.nm.gdrive_backup.domain.model.DownloadFailure;
import org.nm.gdrive_backup.domain.port.out.DownloadFailurePort;
import org.springframework.stereotype.Component;

/** Reads the report of skipped files; a run writes it inside its commit, see {@link SqliteSyncCommitAdapter}. */
@Component
public class SqliteDownloadFailureAdapter implements DownloadFailurePort {

	private static final String COLUMNS = "id, scope_key, file_id, file_name, drive_path, reason, failed_at, "
			+ "archive_id, open, resolved_at";

	private final SqliteDatabase database;

	public SqliteDownloadFailureAdapter(SqliteDatabase database) {
		this.database = database;
	}

	@Override
	public List<DownloadFailure> findOpenByScopeKey(String scopeKey) {
		try (var connection = database.openConnection();
			var statement = connection.prepareStatement(
					"SELECT " + COLUMNS + " FROM download_failures WHERE scope_key = ? AND open = 1 ORDER BY id")) {
			statement.setString(1, scopeKey);
			return read(statement.executeQuery());
		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to read SQLite download failures", exception);
		}
	}

	@Override
	public List<DownloadFailure> findOpen() {
		try (var connection = database.openConnection();
			var statement = connection.prepareStatement(
					"SELECT " + COLUMNS + " FROM download_failures WHERE open = 1 ORDER BY id DESC")) {
			return read(statement.executeQuery());
		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to read SQLite download failures", exception);
		}
	}

	private static List<DownloadFailure> read(ResultSet result) throws SQLException {
		try (result) {
			List<DownloadFailure> failures = new ArrayList<>();
			while (result.next()) {
				long rawArchiveId = result.getLong("archive_id");
				Long archiveId = result.wasNull() ? null : rawArchiveId;
				String resolvedAt = result.getString("resolved_at");
				failures.add(new DownloadFailure(result.getLong("id"), result.getString("scope_key"),
						result.getString("file_id"), result.getString("file_name"), result.getString("drive_path"),
						result.getString("reason"), Instant.parse(result.getString("failed_at")), archiveId,
						result.getBoolean("open"), resolvedAt == null ? null : Instant.parse(resolvedAt)));
			}
			return failures;
		}
	}
}
