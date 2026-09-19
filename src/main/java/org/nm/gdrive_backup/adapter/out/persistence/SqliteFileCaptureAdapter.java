package org.nm.gdrive_backup.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.nm.gdrive_backup.domain.model.FileCapture;
import org.nm.gdrive_backup.domain.port.out.FileCapturePort;
import org.springframework.stereotype.Component;

@Component
public class SqliteFileCaptureAdapter implements FileCapturePort {

	private static final String SELECT_COLUMNS =
			"SELECT id, file_id, revision_id, timestamp, archive_id, entry_name, size_bytes FROM file_captures ";

	private final SqliteDatabase database;

	public SqliteFileCaptureAdapter(SqliteDatabase database) {
		this.database = database;
	}

	@Override
	public FileCapture save(FileCapture capture) {
		try (var connection = database.openConnection();
			var statement = connection.prepareStatement(
					"INSERT INTO file_captures(file_id, revision_id, timestamp, archive_id, entry_name, size_bytes) "
							+ "VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
			statement.setString(1, capture.fileId());
			statement.setString(2, capture.revisionId());
			statement.setString(3, capture.timestamp().toString());
			if (capture.archiveId() == null) {
				statement.setNull(4, java.sql.Types.INTEGER);
			} else {
				statement.setLong(4, capture.archiveId());
			}
			statement.setString(5, capture.entryName());
			statement.setLong(6, capture.sizeBytes());
			statement.executeUpdate();
			try (ResultSet keys = statement.getGeneratedKeys()) {
				if (!keys.next()) {
					throw new IllegalStateException("SQLite did not return a file capture id");
				}
				return new FileCapture(keys.getLong(1), capture.fileId(), capture.revisionId(),
						capture.timestamp(), capture.archiveId(), capture.entryName(), capture.sizeBytes());
			}
		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to write SQLite file capture", exception);
		}
	}

	@Override
	public List<FileCapture> findByFileId(String fileId) {
		try (var connection = database.openConnection();
			var statement = connection.prepareStatement(SELECT_COLUMNS + "WHERE file_id = ? ORDER BY id")) {
			statement.setString(1, fileId);
			try (ResultSet result = statement.executeQuery()) {
				List<FileCapture> captures = new ArrayList<>();
				while (result.next()) {
					captures.add(readCapture(result));
				}
				return captures;
			}
		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to read SQLite file captures", exception);
		}
	}

	@Override
	public Optional<FileCapture> findById(Long id) {
		try (var connection = database.openConnection();
			var statement = connection.prepareStatement(SELECT_COLUMNS + "WHERE id = ?")) {
			statement.setLong(1, id);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				return Optional.of(readCapture(result));
			}
		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to read SQLite file capture", exception);
		}
	}

	private static FileCapture readCapture(ResultSet result) throws SQLException {
		long rawArchiveId = result.getLong("archive_id");
		Long archiveId = result.wasNull() ? null : rawArchiveId;
		return new FileCapture(
				result.getLong("id"), result.getString("file_id"), result.getString("revision_id"),
				Instant.parse(result.getString("timestamp")), archiveId, result.getString("entry_name"),
				result.getLong("size_bytes"));
	}
}
