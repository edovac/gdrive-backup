package org.nm.gdrive_backup.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import org.nm.gdrive_backup.domain.model.SyncState;
import org.nm.gdrive_backup.domain.port.out.SyncStatePort;
import org.springframework.stereotype.Component;

@Component
public class SqliteSyncStateAdapter implements SyncStatePort {

	private final SqliteDatabase database;

	public SqliteSyncStateAdapter(SqliteDatabase database) {
		this.database = database;
	}

	@Override
	public Optional<SyncState> findByScopeKey(String scopeKey) {
		try (var connection = database.openConnection();
			var statement = connection.prepareStatement(
					"SELECT scope_key, page_token FROM sync_state WHERE scope_key = ?")) {
			statement.setString(1, scopeKey);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				return Optional.of(new SyncState(result.getString("scope_key"), result.getString("page_token")));
			}
		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to read SQLite sync state", exception);
		}
	}

	@Override
	public void save(SyncState state) {
		try (var connection = database.openConnection();
			var statement = connection.prepareStatement(
					"INSERT INTO sync_state(scope_key, page_token) VALUES (?, ?) "
							+ "ON CONFLICT(scope_key) DO UPDATE SET page_token = excluded.page_token")) {
			statement.setString(1, state.scopeKey());
			statement.setString(2, state.pageToken());
			statement.executeUpdate();
		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to write SQLite sync state", exception);
		}
	}

	@Override
	public void deleteByScopeKey(String scopeKey) {
		try (var connection = database.openConnection();
			var statement = connection.prepareStatement("DELETE FROM sync_state WHERE scope_key = ?")) {
			statement.setString(1, scopeKey);
			statement.executeUpdate();
		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to delete SQLite sync state", exception);
		}
	}
}
