package org.nm.gdrive_backup.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nm.gdrive_backup.domain.model.SyncState;

class SqliteSyncStateAdapterTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void savesAndUpdatesPageTokenByScope() {
		SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("backup.db"));
		database.initialize();
		SqliteSyncStateAdapter adapter = new SqliteSyncStateAdapter(database);

		assertTrue(adapter.findByScopeKey("user@example.com").isEmpty());
		adapter.save(new SyncState("user@example.com", "token-1"));
		adapter.save(new SyncState("user@example.com", "token-2"));

		assertEquals(Optional.of(new SyncState("user@example.com", "token-2")),
				adapter.findByScopeKey("user@example.com"));
	}

	@Test
	void deletesPageTokenByScope() {
		SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("backup.db"));
		database.initialize();
		SqliteSyncStateAdapter adapter = new SqliteSyncStateAdapter(database);
		adapter.save(new SyncState("user@example.com", "token-1"));

		adapter.deleteByScopeKey("user@example.com");

		assertTrue(adapter.findByScopeKey("user@example.com").isEmpty());
	}
}
