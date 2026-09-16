package org.nm.gdrive_backup.domain.port.out;

import java.util.List;

import org.nm.gdrive_backup.domain.model.Archive;

public interface ArchivePort {

	Archive save(Archive archive);

	/** Ordered by sequence_number ascending. */
	List<Archive> findByScopeKey(String scopeKey);
}
