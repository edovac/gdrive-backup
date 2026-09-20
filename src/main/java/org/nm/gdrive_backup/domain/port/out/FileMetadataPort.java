package org.nm.gdrive_backup.domain.port.out;

import java.util.List;
import java.util.Optional;

import org.nm.gdrive_backup.domain.model.StoredFile;

public interface FileMetadataPort {

	Optional<StoredFile> findByFileId(String fileId);

	List<StoredFile> findAllByOwnerScope(String ownerScope);

	/** Files whose name contains the text, case-insensitively, ordered by name and then id. */
	List<StoredFile> searchByName(String query, int limit);

	void save(StoredFile file);
}
