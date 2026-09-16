package org.nm.gdrive_backup.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nm.gdrive_backup.domain.model.StoredFile;

class SqliteFileMetadataAdapterTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void savesAndUpdatesFileMetadata() {
		SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("backup.db"));
		database.initialize();
		SqliteFileMetadataAdapter adapter = new SqliteFileMetadataAdapter(database);
		StoredFile original = new StoredFile(
				"file-1", "user@example.com", "Report", "root", null,
				"application/pdf", false, "revision-1", null);
		StoredFile renamed = new StoredFile(
				"file-1", "user@example.com", "Renamed report", "folder-1", null,
				"application/pdf", true, "revision-2", null);

		assertTrue(adapter.findByFileId("file-1").isEmpty());
		adapter.save(original);
		adapter.save(renamed);

		assertEquals(Optional.of(renamed), adapter.findByFileId("file-1"));
	}

	@Test
	void findsAllFilesForAnOwnerScopeIncludingFoldersAndTrashedRows() {
		SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("backup.db"));
		database.initialize();
		SqliteFileMetadataAdapter adapter = new SqliteFileMetadataAdapter(database);
		StoredFile folder = new StoredFile("folder-1", "user@example.com", "Reports", "root", null,
				"application/vnd.google-apps.folder", false, null, null);
		StoredFile trashed = new StoredFile("file-1", "user@example.com", "Old report", "folder-1", null,
				"application/pdf", true, "revision-1", null);
		StoredFile otherScope = new StoredFile("file-2", "drive-1", "Budget", "root", "drive-1",
				"application/pdf", false, "revision-1", null);
		adapter.save(folder);
		adapter.save(trashed);
		adapter.save(otherScope);

		List<StoredFile> found = adapter.findAllByOwnerScope("user@example.com");

		assertEquals(List.of(trashed, folder), found);
	}
}