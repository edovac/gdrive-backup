package org.nm.gdrive_backup.adapter.in.javafx;

import java.time.Instant;
import java.time.ZoneId;

import org.nm.gdrive_backup.domain.model.HistoryEntry;
import org.nm.gdrive_backup.domain.model.HistoryEntryKind;
import org.nm.gdrive_backup.domain.model.StoredFile;

/** Wording for the History view, kept free of JavaFX so it can be unit tested. */
final class FileHistoryText {

	private FileHistoryText() {
	}

	static String when(Instant instant, ZoneId zone) {
		return ArchiveManagerText.created(instant, zone);
	}

	static String what(HistoryEntry entry) {
		if (entry.kind() == HistoryEntryKind.CAPTURE) {
			return "Content captured (revision " + entry.revisionId() + ", "
					+ ArchiveManagerText.size(entry.sizeBytes()) + ")";
		}
		return switch (entry.eventType()) {
			case "rename" -> "Renamed '" + entry.oldValue() + "' → '" + entry.newValue() + "'";
			case "move" -> "Moved from " + folders(entry.oldValue()) + " to " + folders(entry.newValue());
			case "trash" -> "Moved to trash";
			case "untrash" -> "Restored from trash";
			case "content" -> "Content changed (revision " + entry.newValue() + ")";
			case "delete" -> "Deleted from Drive";
			default -> entry.eventType();
		};
	}

	static String archive(HistoryEntry entry) {
		if (entry.archiveSequence() == null) {
			return "Unknown archive";
		}
		return "#" + entry.archiveSequence() + " " + ArchiveManagerText.kind(entry.archiveMode());
	}

	static String status(StoredFile file) {
		return file.trashed() ? "In trash" : "";
	}

	private static String folders(String names) {
		return names == null || names.isBlank() ? "(no folder)" : names;
	}
}
