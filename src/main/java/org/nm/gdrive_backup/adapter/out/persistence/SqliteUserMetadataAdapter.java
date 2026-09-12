package org.nm.gdrive_backup.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.nm.gdrive_backup.domain.model.StoredUser;
import org.nm.gdrive_backup.domain.port.out.UserMetadataPort;
import org.springframework.stereotype.Component;

@Component
public class SqliteUserMetadataAdapter implements UserMetadataPort {

	private final SqliteDatabase database;

	public SqliteUserMetadataAdapter(SqliteDatabase database) {
		this.database = database;
	}

	@Override
	public Optional<StoredUser> findByEmail(String email) {
		try (var connection = database.openConnection();
			var statement = connection.prepareStatement(
					"SELECT email, display_name, last_synced_at FROM users WHERE email = ?")) {
			statement.setString(1, email);
			try (ResultSet result = statement.executeQuery()) {
				return result.next() ? Optional.of(readUser(result)) : Optional.empty();
			}
		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to read SQLite user metadata", exception);
		}
	}

	@Override
	public List<StoredUser> findAll() {
		try (var connection = database.openConnection();
			var statement = connection.createStatement();
			ResultSet result = statement.executeQuery(
					"SELECT email, display_name, last_synced_at FROM users ORDER BY email")) {
			List<StoredUser> users = new ArrayList<>();
			while (result.next()) {
				users.add(readUser(result));
			}
			return users;
		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to list SQLite users", exception);
		}
	}

	@Override
	public void save(StoredUser user) {
		try (var connection = database.openConnection();
			var statement = connection.prepareStatement(
					"INSERT INTO users(email, display_name, last_synced_at) VALUES (?, ?, ?) "
							+ "ON CONFLICT(email) DO UPDATE SET display_name = excluded.display_name, "
							+ "last_synced_at = excluded.last_synced_at")) {
			statement.setString(1, user.email());
			statement.setString(2, user.displayName());
			statement.setString(3, user.lastSyncedAt() == null ? null : user.lastSyncedAt().toString());
			statement.executeUpdate();
		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to write SQLite user metadata", exception);
		}
	}

	private static StoredUser readUser(ResultSet result) throws SQLException {
		String timestamp = result.getString("last_synced_at");
		return new StoredUser(result.getString("email"), result.getString("display_name"),
				timestamp == null ? null : Instant.parse(timestamp));
	}
}