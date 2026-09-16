package org.nm.gdrive_backup.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.port.out.FileMetadataPort;
import org.springframework.stereotype.Component;

@Component
public class SqliteFileMetadataAdapter implements FileMetadataPort {

	private final SqliteDatabase database;

	public SqliteFileMetadataAdapter(SqliteDatabase database) {
		this.database = database;
	}

	@Override
	public Optional<StoredFile> findByFileId(String fileId) {
		try (var connection = database.openConnection();
			var statement = connection.prepareStatement(
					"SELECT file_id, owner_scope, name, parents, drive_id, mime_type, trashed, "
							+ "head_revision_id, current_version_id FROM files WHERE file_id = ?")) {
			statement.setString(1, fileId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				return Optional.of(readFile(result));
			}
		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to read SQLite file metadata", exception);
		}
	}

	@Override
	public List<StoredFile> findAllByOwnerScope(String ownerScope) {
		try (var connection = database.openConnection();
			var statement = connection.prepareStatement(
					"SELECT file_id, owner_scope, name, parents, drive_id, mime_type, trashed, "
							+ "head_revision_id, current_version_id FROM files WHERE owner_scope = ? "
							+ "ORDER BY file_id")) {
			statement.setString(1, ownerScope);
			try (ResultSet result = statement.executeQuery()) {
				List<StoredFile> files = new ArrayList<>();
				while (result.next()) {
					files.add(readFile(result));
				}
				return files;
			}
		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to read SQLite file metadata", exception);
		}
	}

	@Override
	public void save(StoredFile file) {
		try (var connection = database.openConnection();
			var statement = connection.prepareStatement(
					"INSERT INTO files(file_id, owner_scope, name, parents, drive_id, mime_type, trashed, "
							+ "head_revision_id, current_version_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
							+ "ON CONFLICT(file_id) DO UPDATE SET owner_scope = excluded.owner_scope, "
							+ "name = excluded.name, parents = excluded.parents, drive_id = excluded.drive_id, "
							+ "mime_type = excluded.mime_type, trashed = excluded.trashed, "
							+ "head_revision_id = excluded.head_revision_id, "
							+ "current_version_id = excluded.current_version_id")) {
			statement.setString(1, file.fileId());
			statement.setString(2, file.ownerScope());
			statement.setString(3, file.name());
			statement.setString(4, file.parents());
			statement.setString(5, file.driveId());
			statement.setString(6, file.mimeType());
			statement.setBoolean(7, file.trashed());
			statement.setString(8, file.headRevisionId());
			if (file.currentVersionId() == null) {
				statement.setObject(9, null);
			} else {
				statement.setLong(9, file.currentVersionId());
			}
			statement.executeUpdate();
		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to write SQLite file metadata", exception);
		}
	}

	private static StoredFile readFile(ResultSet result) throws SQLException {
		long currentVersionId = result.getLong("current_version_id");
		Long versionId = result.wasNull() ? null : currentVersionId;
		return new StoredFile(
				result.getString("file_id"),
				result.getString("owner_scope"),
				result.getString("name"),
				result.getString("parents"),
				result.getString("drive_id"),
				result.getString("mime_type"),
				result.getBoolean("trashed"),
				result.getString("head_revision_id"),
				versionId);
	}
}