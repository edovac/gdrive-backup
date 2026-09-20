package org.nm.gdrive_backup.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.nm.gdrive_backup.domain.model.FileEvent;
import org.nm.gdrive_backup.domain.port.out.FileEventPort;
import org.springframework.stereotype.Component;

@Component
public class SqliteFileEventAdapter implements FileEventPort {

	private final SqliteDatabase database;

	public SqliteFileEventAdapter(SqliteDatabase database) {
		this.database = database;
	}

	@Override
	public FileEvent save(FileEvent event) {
		try (var connection = database.openConnection();
			var statement = connection.prepareStatement(
					"INSERT INTO file_events(file_id, event_type, old_value, new_value, timestamp, archive_id) "
							+ "VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
			statement.setString(1, event.fileId());
			statement.setString(2, event.eventType());
			statement.setString(3, event.oldValue());
			statement.setString(4, event.newValue());
			statement.setString(5, event.timestamp().toString());
			if (event.archiveId() == null) {
				statement.setNull(6, java.sql.Types.INTEGER);
			} else {
				statement.setLong(6, event.archiveId());
			}
			statement.executeUpdate();
			try (ResultSet keys = statement.getGeneratedKeys()) {
				if (!keys.next()) {
					throw new IllegalStateException("SQLite did not return a file event id");
				}
				return new FileEvent(keys.getLong(1), event.fileId(), event.eventType(), event.oldValue(),
						event.newValue(), event.timestamp(), event.archiveId());
			}
		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to write SQLite file event", exception);
		}
	}

	@Override
	public List<FileEvent> findByFileId(String fileId) {
		try (var connection = database.openConnection();
			var statement = connection.prepareStatement(
					"SELECT id, file_id, event_type, old_value, new_value, timestamp, archive_id "
							+ "FROM file_events WHERE file_id = ? ORDER BY id")) {
			statement.setString(1, fileId);
			try (ResultSet result = statement.executeQuery()) {
				List<FileEvent> events = new ArrayList<>();
				while (result.next()) {
					long rawArchiveId = result.getLong("archive_id");
					Long archiveId = result.wasNull() ? null : rawArchiveId;
					events.add(new FileEvent(
							result.getLong("id"), result.getString("file_id"), result.getString("event_type"),
							result.getString("old_value"), result.getString("new_value"),
							Instant.parse(result.getString("timestamp")), archiveId));
				}
				return events;
			}
		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to read SQLite file events", exception);
		}
	}

	@Override
	public int countByArchiveId(long archiveId) {
		try (var connection = database.openConnection();
			var statement = connection.prepareStatement("SELECT COUNT(*) FROM file_events WHERE archive_id = ?")) {
			statement.setLong(1, archiveId);
			try (ResultSet result = statement.executeQuery()) {
				result.next();
				return result.getInt(1);
			}
		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to count SQLite file events", exception);
		}
	}
}
