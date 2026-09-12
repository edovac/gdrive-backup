package org.nm.gdrive_backup.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.nm.gdrive_backup.domain.model.FileVersion;
import org.nm.gdrive_backup.domain.port.out.FileVersionPort;
import org.springframework.stereotype.Component;

@Component
public class SqliteFileVersionAdapter implements FileVersionPort {

	private final SqliteDatabase database;

	public SqliteFileVersionAdapter(SqliteDatabase database) {
		this.database = database;
	}

	@Override
	public FileVersion save(FileVersion version) {
		try (var connection = database.openConnection();
			var statement = connection.prepareStatement(
					"INSERT INTO file_versions(file_id, revision_id, timestamp, local_path, size_bytes) "
							+ "VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
			statement.setString(1, version.fileId());
			statement.setString(2, version.revisionId());
			statement.setString(3, version.timestamp().toString());
			statement.setString(4, version.localPath());
			statement.setLong(5, version.sizeBytes());
			statement.executeUpdate();
			try (ResultSet keys = statement.getGeneratedKeys()) {
				if (!keys.next()) {
					throw new IllegalStateException("SQLite did not return a file version id");
				}
				return new FileVersion(keys.getLong(1), version.fileId(), version.revisionId(),
						version.timestamp(), version.localPath(), version.sizeBytes());
			}
		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to write SQLite file version", exception);
		}
	}

	@Override
	public List<FileVersion> findByFileId(String fileId) {
		try (var connection = database.openConnection();
			var statement = connection.prepareStatement(
					"SELECT id, file_id, revision_id, timestamp, local_path, size_bytes "
							+ "FROM file_versions WHERE file_id = ? ORDER BY id")) {
			statement.setString(1, fileId);
			try (ResultSet result = statement.executeQuery()) {
				List<FileVersion> versions = new ArrayList<>();
				while (result.next()) {
					versions.add(new FileVersion(
							result.getLong("id"), result.getString("file_id"), result.getString("revision_id"),
							Instant.parse(result.getString("timestamp")), result.getString("local_path"),
							result.getLong("size_bytes")));
				}
				return versions;
			}
		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to read SQLite file versions", exception);
		}
	}
}