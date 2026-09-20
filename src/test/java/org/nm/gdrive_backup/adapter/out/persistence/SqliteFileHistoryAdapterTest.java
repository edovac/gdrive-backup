package org.nm.gdrive_backup.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nm.gdrive_backup.domain.model.DriveScopeType;
import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.RevisionMode;
import org.nm.gdrive_backup.domain.model.FileEvent;
import org.nm.gdrive_backup.domain.model.FileCapture;
import org.nm.gdrive_backup.domain.model.StoredFile;

class SqliteFileHistoryAdapterTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void storesCapturesAndEventsForAFileInInsertionOrder() {
		SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("backup.db"));
		database.initialize();
		new SqliteFileMetadataAdapter(database).save(new StoredFile(
				"file-1", "user@example.com", "Report", "root", null,
				"application/pdf", false, "revision-1", null));
		long archiveId = saveArchive(database);
		SqliteFileCaptureAdapter captures = new SqliteFileCaptureAdapter(database);
		SqliteFileEventAdapter events = new SqliteFileEventAdapter(database);
		Instant firstTimestamp = Instant.parse("2026-09-12T10:00:00Z");
		Instant secondTimestamp = Instant.parse("2026-09-12T11:00:00Z");

		FileCapture firstCapture = captures.save(new FileCapture(
				null, "file-1", "revision-1", firstTimestamp, archiveId, "entry-1.pdf", 100L));
		FileCapture secondCapture = captures.save(new FileCapture(
				null, "file-1", "revision-2", secondTimestamp, archiveId, "entry-2.pdf", 120L));
		FileEvent rename = events.save(new FileEvent(
				null, "file-1", "rename", "Report", "Renamed report", secondTimestamp, archiveId));

		assertNotNull(firstCapture.id());
		assertNotNull(secondCapture.id());
		assertNotNull(rename.id());
		assertEquals(List.of(firstCapture, secondCapture), captures.findByFileId("file-1"));
		assertEquals(List.of(rename), events.findByFileId("file-1"));
	}

	@Test
	void findsACaptureByItsOwnId() {
		SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("backup.db"));
		database.initialize();
		new SqliteFileMetadataAdapter(database).save(new StoredFile(
				"file-1", "user@example.com", "Report", "root", null,
				"application/pdf", false, "revision-1", null));
		long archiveId = saveArchive(database);
		SqliteFileCaptureAdapter captures = new SqliteFileCaptureAdapter(database);
		FileCapture saved = captures.save(new FileCapture(
				null, "file-1", "revision-1", Instant.parse("2026-09-12T10:00:00Z"), archiveId, "entry-1.pdf", 100L));

		Optional<FileCapture> found = captures.findById(saved.id());

		assertEquals(Optional.of(saved), found);
		assertTrue(captures.findById(saved.id() + 999).isEmpty());
	}

	private static long saveArchive(SqliteDatabase database) {
		return new SqliteArchiveAdapter(database).save(new Archive(null, "user@example.com", DriveScopeType.PERSONAL, 1, null,
				ArchiveMode.FULL, RevisionMode.LATEST_ONLY, Instant.parse("2026-09-12T09:00:00Z"),
				"archives/x/archive-0001-full.zip", null, null, false)).id();
	}
}
