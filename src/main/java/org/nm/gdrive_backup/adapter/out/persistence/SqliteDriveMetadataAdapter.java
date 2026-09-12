package org.nm.gdrive_backup.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.nm.gdrive_backup.domain.model.StoredDrive;
import org.nm.gdrive_backup.domain.port.out.DriveMetadataPort;
import org.springframework.stereotype.Component;

@Component
public class SqliteDriveMetadataAdapter implements DriveMetadataPort {

	private final SqliteDatabase database;

	public SqliteDriveMetadataAdapter(SqliteDatabase database) {
		this.database = database;
	}

	@Override
	public List<StoredDrive> findAll() {
		try (var connection = database.openConnection();
			var statement = connection.createStatement();
			ResultSet result = statement.executeQuery(
					"SELECT drive_id, name, last_synced_at FROM drives ORDER BY name")) {
			List<StoredDrive> drives = new ArrayList<>();
			while (result.next()) {
				String timestamp = result.getString("last_synced_at");
				drives.add(new StoredDrive(result.getString("drive_id"), result.getString("name"),
						timestamp == null ? null : Instant.parse(timestamp)));
			}
			return drives;
		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to list SQLite drives", exception);
		}
	}

	@Override
	public void save(StoredDrive drive) {
		try (var connection = database.openConnection();
			var statement = connection.prepareStatement(
					"INSERT INTO drives(drive_id, name, last_synced_at) VALUES (?, ?, ?) "
							+ "ON CONFLICT(drive_id) DO UPDATE SET name = excluded.name, "
							+ "last_synced_at = excluded.last_synced_at")) {
			statement.setString(1, drive.driveId());
			statement.setString(2, drive.name());
			statement.setString(3, drive.lastSyncedAt() == null ? null : drive.lastSyncedAt().toString());
			statement.executeUpdate();
		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to write SQLite drive metadata", exception);
		}
	}
}