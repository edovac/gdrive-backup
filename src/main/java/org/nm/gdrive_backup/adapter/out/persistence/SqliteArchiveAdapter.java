package org.nm.gdrive_backup.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.DriveScopeType;
import org.nm.gdrive_backup.domain.model.RevisionMode;
import org.nm.gdrive_backup.domain.port.out.ArchivePort;
import org.springframework.stereotype.Component;

@Component
public class SqliteArchiveAdapter implements ArchivePort {

	private static final String SELECT_COLUMNS =
			"SELECT id, scope_key, scope_type, sequence_number, base_archive_id, mode, revision_mode, created_at, "
					+ "archive_path, from_page_token, to_page_token, cancelled FROM archives ";

	private final SqliteDatabase database;

	public SqliteArchiveAdapter(SqliteDatabase database) {
		this.database = database;
	}

	@Override
	public Archive save(Archive archive) {
		try (var connection = database.openConnection();
			var statement = connection.prepareStatement(
					"INSERT INTO archives(scope_key, scope_type, sequence_number, base_archive_id, mode, revision_mode, "
							+ "created_at, archive_path, from_page_token, to_page_token, cancelled) "
							+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
			statement.setString(1, archive.scopeKey());
			statement.setString(2, archive.scopeType().name());
			statement.setInt(3, archive.sequenceNumber());
			setNullableLong(statement, 4, archive.baseArchiveId());
			statement.setString(5, archive.mode().name());
			statement.setString(6, archive.revisionMode().name());
			statement.setString(7, archive.createdAt().toString());
			setNullableString(statement, 8, archive.archivePath());
			setNullableString(statement, 9, archive.fromPageToken());
			setNullableString(statement, 10, archive.toPageToken());
			statement.setBoolean(11, archive.cancelled());
			statement.executeUpdate();
			try (ResultSet keys = statement.getGeneratedKeys()) {
				if (!keys.next()) {
					throw new IllegalStateException("SQLite did not return an archive id");
				}
				return new Archive(keys.getLong(1), archive.scopeKey(), archive.scopeType(), archive.sequenceNumber(),
						archive.baseArchiveId(), archive.mode(), archive.revisionMode(), archive.createdAt(),
						archive.archivePath(), archive.fromPageToken(), archive.toPageToken(), archive.cancelled());
			}
		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to write SQLite archive", exception);
		}
	}

	@Override
	public List<Archive> findByScopeKey(String scopeKey) {
		return query(SELECT_COLUMNS + "WHERE scope_key = ? ORDER BY sequence_number", scopeKey);
	}

	@Override
	public List<Archive> findAll() {
		return query(SELECT_COLUMNS + "ORDER BY scope_key, sequence_number", null);
	}

	@Override
	public List<Long> findSourceArchiveIds(long archiveId) {
		try (var connection = database.openConnection();
			var statement = connection.prepareStatement(
					"SELECT source_archive_id FROM archive_sources WHERE archive_id = ? ORDER BY source_archive_id")) {
			statement.setLong(1, archiveId);
			try (ResultSet result = statement.executeQuery()) {
				List<Long> ids = new ArrayList<>();
				while (result.next()) {
					ids.add(result.getLong(1));
				}
				return ids;
			}
		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to read SQLite archive sources", exception);
		}
	}

	private List<Archive> query(String sql, String scopeKeyOrNull) {
		try (var connection = database.openConnection();
			var statement = connection.prepareStatement(sql)) {
			if (scopeKeyOrNull != null) {
				statement.setString(1, scopeKeyOrNull);
			}
			try (ResultSet result = statement.executeQuery()) {
				List<Archive> archives = new ArrayList<>();
				while (result.next()) {
					archives.add(readArchive(result));
				}
				return archives;
			}
		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to read SQLite archives", exception);
		}
	}

	private static Archive readArchive(ResultSet result) throws SQLException {
		long rawBaseArchiveId = result.getLong("base_archive_id");
		Long baseArchiveId = result.wasNull() ? null : rawBaseArchiveId;
		return new Archive(
				result.getLong("id"),
				result.getString("scope_key"),
				DriveScopeType.valueOf(result.getString("scope_type")),
				result.getInt("sequence_number"),
				baseArchiveId,
				ArchiveMode.valueOf(result.getString("mode")),
				RevisionMode.valueOf(result.getString("revision_mode")),
				Instant.parse(result.getString("created_at")),
				result.getString("archive_path"),
				result.getString("from_page_token"),
				result.getString("to_page_token"),
				result.getBoolean("cancelled"));
	}

	private static void setNullableLong(java.sql.PreparedStatement statement, int index, Long value)
			throws SQLException {
		if (value == null) {
			statement.setNull(index, Types.INTEGER);
		} else {
			statement.setLong(index, value);
		}
	}

	private static void setNullableString(java.sql.PreparedStatement statement, int index, String value)
			throws SQLException {
		if (value == null) {
			statement.setNull(index, Types.VARCHAR);
		} else {
			statement.setString(index, value);
		}
	}
}
