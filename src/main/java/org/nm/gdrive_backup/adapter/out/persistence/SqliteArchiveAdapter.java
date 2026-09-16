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
import org.nm.gdrive_backup.domain.model.RevisionMode;
import org.nm.gdrive_backup.domain.port.out.ArchivePort;
import org.springframework.stereotype.Component;

@Component
public class SqliteArchiveAdapter implements ArchivePort {

	private final SqliteDatabase database;

	public SqliteArchiveAdapter(SqliteDatabase database) {
		this.database = database;
	}

	@Override
	public Archive save(Archive archive) {
		try (var connection = database.openConnection();
			var statement = connection.prepareStatement(
					"INSERT INTO archives(scope_key, sequence_number, base_archive_id, mode, revision_mode, "
							+ "created_at, archive_path, from_page_token, to_page_token, cancelled) "
							+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
			statement.setString(1, archive.scopeKey());
			statement.setInt(2, archive.sequenceNumber());
			setNullableLong(statement, 3, archive.baseArchiveId());
			statement.setString(4, archive.mode().name());
			statement.setString(5, archive.revisionMode().name());
			statement.setString(6, archive.createdAt().toString());
			setNullableString(statement, 7, archive.archivePath());
			setNullableString(statement, 8, archive.fromPageToken());
			setNullableString(statement, 9, archive.toPageToken());
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
		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to write SQLite archive", exception);
		}
	}

	@Override
	public List<Archive> findByScopeKey(String scopeKey) {
		try (var connection = database.openConnection();
			var statement = connection.prepareStatement(
					"SELECT id, scope_key, sequence_number, base_archive_id, mode, revision_mode, created_at, "
							+ "archive_path, from_page_token, to_page_token, cancelled FROM archives "
							+ "WHERE scope_key = ? ORDER BY sequence_number")) {
			statement.setString(1, scopeKey);
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
