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

	@Test
	void searchesByNameCaseInsensitivelyWithEscapingAndLimit() {
		SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("backup.db"));
		database.initialize();
		SqliteFileMetadataAdapter adapter = new SqliteFileMetadataAdapter(database);
		adapter.save(file("f1", "Budget 2026.xlsx"));
		adapter.save(file("f2", "budget notes.txt"));
		adapter.save(file("f3", "100% done"));
		adapter.save(file("f4", "1000 done"));
		adapter.save(file("f5", "snake_case"));
		adapter.save(file("f6", "snakeXcase"));

		assertEquals(List.of("Budget 2026.xlsx", "budget notes.txt"),
				adapter.searchByName("BUDGET", 10).stream().map(StoredFile::name).toList());
		assertEquals(List.of("100% done"), adapter.searchByName("0%", 10).stream().map(StoredFile::name).toList());
		assertEquals(List.of("snake_case"), adapter.searchByName("e_c", 10).stream().map(StoredFile::name).toList());
		assertEquals(1, adapter.searchByName("budget", 1).size());
		assertTrue(adapter.searchByName("missing", 10).isEmpty());
	}

	private static StoredFile file(String id, String name) {
		return new StoredFile(id, "user@example.com", name, "root", null, "text/plain", false, "rev", null);
	}
}
