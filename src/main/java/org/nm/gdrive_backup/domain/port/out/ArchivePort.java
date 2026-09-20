package org.nm.gdrive_backup.domain.port.out;

import java.util.List;

import org.nm.gdrive_backup.domain.model.Archive;

public interface ArchivePort {

	Archive save(Archive archive);

	/** Ordered by sequence_number ascending. */
	List<Archive> findByScopeKey(String scopeKey);

	/** Every archive of every drive, ordered by scope then sequence_number. */
	List<Archive> findAll();

	/** The archives a merged archive was built from, by id; empty for any other archive. */
	List<Long> findSourceArchiveIds(long archiveId);
}
