package org.nm.gdrive_backup.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nm.gdrive_backup.domain.model.StoredDrive;
import org.nm.gdrive_backup.domain.model.StoredUser;

class SqliteUserDriveMetadataAdapterTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void upsertsUsersAndListsThemByEmail() {
		SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("backup.db"));
		database.initialize();
		SqliteUserMetadataAdapter adapter = new SqliteUserMetadataAdapter(database);
		Instant syncedAt = Instant.parse("2026-09-12T12:00:00Z");

		adapter.save(new StoredUser("zara@example.com", "Zara", null));
		adapter.save(new StoredUser("alice@example.com", "Alice", syncedAt));
		adapter.save(new StoredUser("zara@example.com", "Zara Updated", syncedAt));

		assertEquals(new StoredUser("zara@example.com", "Zara Updated", syncedAt),
				adapter.findByEmail("zara@example.com").orElseThrow());
		assertEquals(List.of(
				new StoredUser("alice@example.com", "Alice", syncedAt),
				new StoredUser("zara@example.com", "Zara Updated", syncedAt)), adapter.findAll());
	}

	@Test
	void upsertsAndOrdersDrivesByName() {
		SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("backup.db"));
		database.initialize();
		SqliteDriveMetadataAdapter adapter = new SqliteDriveMetadataAdapter(database);

		adapter.save(new StoredDrive("drive-2", "Zeta", null));
		adapter.save(new StoredDrive("drive-1", "Alpha", null));
		adapter.save(new StoredDrive("drive-2", "Beta", null));

		assertTrue(adapter.findAll().contains(new StoredDrive("drive-2", "Beta", null)));
		assertEquals(List.of(
				new StoredDrive("drive-1", "Alpha", null),
				new StoredDrive("drive-2", "Beta", null)), adapter.findAll());
	}
}