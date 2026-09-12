package org.nm.gdrive_backup.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nm.gdrive_backup.domain.model.FileEvent;
import org.nm.gdrive_backup.domain.model.FileVersion;
import org.nm.gdrive_backup.domain.model.StoredFile;

class SqliteFileHistoryAdapterTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void storesVersionsAndEventsForAFileInInsertionOrder() {
		SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("backup.db"));
		database.initialize();
		new SqliteFileMetadataAdapter(database).save(new StoredFile(
				"file-1", "user@example.com", "Report", "root", null,
				"application/pdf", false, "revision-1", null));
		SqliteFileVersionAdapter versions = new SqliteFileVersionAdapter(database);
		SqliteFileEventAdapter events = new SqliteFileEventAdapter(database);
		Instant firstTimestamp = Instant.parse("2026-09-12T10:00:00Z");
		Instant secondTimestamp = Instant.parse("2026-09-12T11:00:00Z");

		FileVersion firstVersion = versions.save(new FileVersion(
				null, "file-1", "revision-1", firstTimestamp, "/backup/report-1.pdf", 100L));
		FileVersion secondVersion = versions.save(new FileVersion(
				null, "file-1", "revision-2", secondTimestamp, "/backup/report-2.pdf", 120L));
		FileEvent rename = events.save(new FileEvent(
				null, "file-1", "rename", "Report", "Renamed report", secondTimestamp));

		assertNotNull(firstVersion.id());
		assertNotNull(secondVersion.id());
		assertNotNull(rename.id());
		assertEquals(List.of(firstVersion, secondVersion), versions.findByFileId("file-1"));
		assertEquals(List.of(rename), events.findByFileId("file-1"));
	}
}