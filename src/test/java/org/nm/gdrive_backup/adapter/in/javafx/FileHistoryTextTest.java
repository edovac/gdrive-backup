package org.nm.gdrive_backup.adapter.in.javafx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.HistoryEntry;
import org.nm.gdrive_backup.domain.model.HistoryEntryKind;
import org.nm.gdrive_backup.domain.model.StoredFile;

class FileHistoryTextTest {

	private static final Instant NOW = Instant.parse("2026-03-04T05:06:00Z");

	@Test
	void describesEachEventType() {
		assertEquals("Renamed 'A' → 'B'", what("rename", "A", "B"));
		assertEquals("Moved from Finance to Archive", what("move", "Finance", "Archive"));
		assertEquals("Moved from (no folder) to Archive", what("move", "", "Archive"));
		assertEquals("Moved to trash", what("trash", "false", "true"));
		assertEquals("Restored from trash", what("untrash", "true", "false"));
		assertEquals("Content changed (revision rev-2)", what("content", "rev-1", "rev-2"));
		assertEquals("Deleted from Drive", what("delete", null, null));
		assertEquals("other", what("other", null, null));
	}

	@Test
	void describesACapture() {
		HistoryEntry capture = new HistoryEntry(NOW, HistoryEntryKind.CAPTURE, null, null, null, "rev-1", 2048L,
				"Report", 4, ArchiveMode.INCREMENTAL, "a.zip");

		assertEquals("Content captured (revision rev-1, 2.0 KB)", FileHistoryText.what(capture));
		assertEquals("#4 Incremental", FileHistoryText.archive(capture));
		assertEquals("2026-03-04 05:06", FileHistoryText.when(NOW, ZoneOffset.UTC));
	}

	@Test
	void marksAnUnknownArchiveAndTrashedFiles() {
		HistoryEntry event = new HistoryEntry(NOW, HistoryEntryKind.EVENT, "trash", "false", "true", null, null,
				null, null, null, null);

		assertEquals("Unknown archive", FileHistoryText.archive(event));
		assertEquals("In trash", FileHistoryText.status(file(true)));
		assertEquals("", FileHistoryText.status(file(false)));
	}

	private static String what(String type, String oldValue, String newValue) {
		return FileHistoryText.what(new HistoryEntry(NOW, HistoryEntryKind.EVENT, type, oldValue, newValue, null,
				null, null, 1, ArchiveMode.FULL, "a.zip"));
	}

	private static StoredFile file(boolean trashed) {
		return new StoredFile("f", "user@example.com", "Report", "root", null, "text/plain", trashed, "rev", null);
	}
}
