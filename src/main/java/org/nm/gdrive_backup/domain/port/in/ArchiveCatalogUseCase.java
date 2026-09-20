package org.nm.gdrive_backup.domain.port.in;

import java.util.List;

import org.nm.gdrive_backup.domain.model.ScopeArchives;

public interface ArchiveCatalogUseCase {

	/** Every drive that has archives, with each archive's state and any problem in its current chain. */
	List<ScopeArchives> listScopes();
}
