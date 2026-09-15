package org.nm.gdrive_backup.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.nm.gdrive_backup.domain.model.FileCapture;
import org.nm.gdrive_backup.domain.port.out.FileCapturePort;
import org.springframework.stereotype.Component;

@Component
public class SqliteFileCaptureAdapter implements FileCapturePort {

	private final SqliteDatabase database;

	public SqliteFileCaptureAdapter(SqliteDatabase database) {
		this.database = database;
	}

	@Override
	public FileCapture save(FileCapture capture) {
		try (var connection = database.openConnection();
			var statement = connection.prepareStatement(
					"INSERT INTO file_captures(file_id, revision_id, timestamp, local_path, size_bytes, archive_id) "
							+ "VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
			statement.setString(1, capture.fileId());
			statement.setString(2, capture.revisionId());
			statement.setString(3, capture.timestamp().toString());
			statement.setString(4, capture.localPath());
			statement.setLong(5, capture.sizeBytes());
			if (capture.archiveId() == null) {
				statement.setNull(6, java.sql.Types.INTEGER);
			} else {
				statement.setLong(6, capture.archiveId());
			}
			statement.executeUpdate();
			try (ResultSet keys = statement.getGeneratedKeys()) {
				if (!keys.next()) {
					throw new IllegalStateException("SQLite did not return a file capture id");
				}
				return new FileCapture(keys.getLong(1), capture.fileId(), capture.revisionId(),
						capture.timestamp(), capture.localPath(), capture.sizeBytes(), capture.archiveId());
			}
		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to write SQLite file capture", exception);
		}
	}

	@Override
	public List<FileCapture> findByFileId(String fileId) {
		try (var connection = database.openConnection();
			var statement = connection.prepareStatement(
					"SELECT id, file_id, revision_id, timestamp, local_path, size_bytes, archive_id "
							+ "FROM file_captures WHERE file_id = ? ORDER BY id")) {
			statement.setString(1, fileId);
			try (ResultSet result = statement.executeQuery()) {
				List<FileCapture> captures = new ArrayList<>();
				while (result.next()) {
					long rawArchiveId = result.getLong("archive_id");
					Long archiveId = result.wasNull() ? null : rawArchiveId;
					captures.add(new FileCapture(
							result.getLong("id"), result.getString("file_id"), result.getString("revision_id"),
							Instant.parse(result.getString("timestamp")), result.getString("local_path"),
							result.getLong("size_bytes"), archiveId));
				}
				return captures;
			}
		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to read SQLite file captures", exception);
		}
	}
}
