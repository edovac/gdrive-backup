package org.nm.gdrive_backup.domain.port.out;

import java.util.Optional;

import org.nm.gdrive_backup.domain.model.SyncState;

public interface SyncStatePort {

	Optional<SyncState> findByScopeKey(String scopeKey);

	void save(SyncState state);
}