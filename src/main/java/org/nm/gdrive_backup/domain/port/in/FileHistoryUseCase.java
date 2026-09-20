package org.nm.gdrive_backup.domain.port.in;

import java.util.List;
import java.util.Optional;

import org.nm.gdrive_backup.domain.model.FileHistory;
import org.nm.gdrive_backup.domain.model.FileSearchResult;

public interface FileHistoryUseCase {

	/** Backed-up files whose name contains the text, case-insensitively; empty for a blank query. */
	List<FileSearchResult> searchFiles(String query);

	/** The file's events and captures in time order, or empty when the file is unknown. */
	Optional<FileHistory> historyOf(String fileId);
}
