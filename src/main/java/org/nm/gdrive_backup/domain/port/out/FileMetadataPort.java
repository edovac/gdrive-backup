package org.nm.gdrive_backup.domain.port.out;

import java.util.Optional;

import org.nm.gdrive_backup.domain.model.StoredFile;

public interface FileMetadataPort {

	Optional<StoredFile> findByFileId(String fileId);

	void save(StoredFile file);
}