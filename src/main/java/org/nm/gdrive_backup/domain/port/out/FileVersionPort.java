package org.nm.gdrive_backup.domain.port.out;

import java.util.List;

import org.nm.gdrive_backup.domain.model.FileVersion;

public interface FileVersionPort {

	FileVersion save(FileVersion version);

	List<FileVersion> findByFileId(String fileId);
}