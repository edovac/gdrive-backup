package org.nm.gdrive_backup.domain.port.out;

import java.util.List;

import org.nm.gdrive_backup.domain.model.FileEvent;

public interface FileEventPort {

	FileEvent save(FileEvent event);

	List<FileEvent> findByFileId(String fileId);

	int countByArchiveId(long archiveId);
}