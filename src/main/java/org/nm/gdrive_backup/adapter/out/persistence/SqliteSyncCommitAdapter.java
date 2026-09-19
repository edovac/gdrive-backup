package org.nm.gdrive_backup.adapter.out.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.FileCapture;
import org.nm.gdrive_backup.domain.model.FileEvent;
import org.nm.gdrive_backup.domain.model.PendingCommit;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.port.out.SyncCommitPort;
import org.springframework.stereotype.Component;

/** Applies a whole run's database effects in one transaction: all of it lands, or none of it does. */
@Component
public class SqliteSyncCommitAdapter implements SyncCommitPort {

	private final SqliteDatabase database;

	public SqliteSyncCommitAdapter(SqliteDatabase database) {
		this.database = database;
	}

	@Override
	public Archive commit(PendingCommit commit) {
		if (commit.archiveOrNull() == null && (!commit.events().isEmpty() || !commit.captures().isEmpty())) {
			throw new IllegalArgumentException("Events and captures need an archive to belong to");
		}
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				Archive saved = insertArchive(connection, commit.archiveOrNull());
				Long archiveId = saved == null ? null : saved.id();
				for (StoredFile file : commit.files()) {
					upsertFile(connection, file);
				}
				for (FileEvent event : commit.events()) {
					insertEvent(connection, event, archiveId);
				}
				Map<String, Long> latestCaptureIdByFile = new HashMap<>();
				for (FileCapture capture : commit.captures()) {
					latestCaptureIdByFile.put(capture.fileId(), insertCapture(connection, capture, archiveId));
				}
				for (Map.Entry<String, Long> entry : latestCaptureIdByFile.entrySet()) {
					setCurrentVersion(connection, entry.getKey(), entry.getValue());
				}
				if (commit.newSyncState() != null) {
					upsertSyncState(connection, commit.newSyncState().scopeKey(), commit.newSyncState().pageToken());
				}
				connection.commit();
				return saved;
			} catch (SQLException | RuntimeException exception) {
				connection.rollback();
				throw exception;
			}
		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to commit backup run to SQLite", exception);
		}
	}

	private static Archive insertArchive(Connection connection, Archive archive) throws SQLException {
		if (archive == null) {
			return null;
		}
		try (var statement = connection.prepareStatement(
				"INSERT INTO archives(scope_key, sequence_number, base_archive_id, mode, revision_mode, "
						+ "created_at, archive_path, from_page_token, to_page_token, cancelled) "
						+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
			statement.setString(1, archive.scopeKey());
			statement.setInt(2, archive.sequenceNumber());
			setNullableLong(statement, 3, archive.baseArchiveId());
			statement.setString(4, archive.mode().name());
			statement.setString(5, archive.revisionMode().name());
			statement.setString(6, archive.createdAt().toString());
			statement.setString(7, archive.archivePath());
			statement.setString(8, archive.fromPageToken());
			statement.setString(9, archive.toPageToken());
			statement.setBoolean(10, archive.cancelled());
			statement.executeUpdate();
			try (ResultSet keys = statement.getGeneratedKeys()) {
				if (!keys.next()) {
					throw new IllegalStateException("SQLite did not return an archive id");
				}
				return new Archive(keys.getLong(1), archive.scopeKey(), archive.sequenceNumber(),
						archive.baseArchiveId(), archive.mode(), archive.revisionMode(), archive.createdAt(),
						archive.archivePath(), archive.fromPageToken(), archive.toPageToken(), archive.cancelled());
			}
		}
	}

	/** current_version_id is deliberately not overwritten here; it only moves when a capture is committed. */
	private static void upsertFile(Connection connection, StoredFile file) throws SQLException {
		try (var statement = connection.prepareStatement(
				"INSERT INTO files(file_id, owner_scope, name, parents, drive_id, mime_type, trashed, "
						+ "head_revision_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
						+ "ON CONFLICT(file_id) DO UPDATE SET owner_scope = excluded.owner_scope, "
						+ "name = excluded.name, parents = excluded.parents, drive_id = excluded.drive_id, "
						+ "mime_type = excluded.mime_type, trashed = excluded.trashed, "
						+ "head_revision_id = excluded.head_revision_id")) {
			statement.setString(1, file.fileId());
			statement.setString(2, file.ownerScope());
			statement.setString(3, file.name());
			statement.setString(4, file.parents());
			statement.setString(5, file.driveId());
			statement.setString(6, file.mimeType());
			statement.setBoolean(7, file.trashed());
			statement.setString(8, file.headRevisionId());
			statement.executeUpdate();
		}
	}

	private static void insertEvent(Connection connection, FileEvent event, Long archiveId) throws SQLException {
		try (var statement = connection.prepareStatement(
				"INSERT INTO file_events(file_id, event_type, old_value, new_value, timestamp, archive_id) "
						+ "VALUES (?, ?, ?, ?, ?, ?)")) {
			statement.setString(1, event.fileId());
			statement.setString(2, event.eventType());
			statement.setString(3, event.oldValue());
			statement.setString(4, event.newValue());
			statement.setString(5, event.timestamp().toString());
			statement.setLong(6, archiveId);
			statement.executeUpdate();
		}
	}

	private static long insertCapture(Connection connection, FileCapture capture, Long archiveId) throws SQLException {
		try (var statement = connection.prepareStatement(
				"INSERT INTO file_captures(file_id, revision_id, timestamp, archive_id, entry_name, size_bytes) "
						+ "VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
			statement.setString(1, capture.fileId());
			statement.setString(2, capture.revisionId());
			statement.setString(3, capture.timestamp().toString());
			statement.setLong(4, archiveId);
			statement.setString(5, capture.entryName());
			statement.setLong(6, capture.sizeBytes());
			statement.executeUpdate();
			try (ResultSet keys = statement.getGeneratedKeys()) {
				if (!keys.next()) {
					throw new IllegalStateException("SQLite did not return a file capture id");
				}
				return keys.getLong(1);
			}
		}
	}

	private static void setCurrentVersion(Connection connection, String fileId, long captureId) throws SQLException {
		try (var statement = connection.prepareStatement(
				"UPDATE files SET current_version_id = ? WHERE file_id = ?")) {
			statement.setLong(1, captureId);
			statement.setString(2, fileId);
			statement.executeUpdate();
		}
	}

	private static void upsertSyncState(Connection connection, String scopeKey, String pageToken)
			throws SQLException {
		try (var statement = connection.prepareStatement(
				"INSERT INTO sync_state(scope_key, page_token) VALUES (?, ?) "
						+ "ON CONFLICT(scope_key) DO UPDATE SET page_token = excluded.page_token")) {
			statement.setString(1, scopeKey);
			statement.setString(2, pageToken);
			statement.executeUpdate();
		}
	}

	private static void setNullableLong(java.sql.PreparedStatement statement, int index, Long value)
			throws SQLException {
		if (value == null) {
			statement.setNull(index, Types.INTEGER);
		} else {
			statement.setLong(index, value);
		}
	}
}
