package org.nm.gdrive_backup.domain.service;

import java.util.List;

import org.nm.gdrive_backup.domain.model.DownloadFailure;
import org.nm.gdrive_backup.domain.port.out.DownloadFailurePort;

/** For wiring that has no database: no failure is ever open. */
enum NoDownloadFailures implements DownloadFailurePort {

	INSTANCE;

	@Override
	public List<DownloadFailure> findOpenByScopeKey(String scopeKey) {
		return List.of();
	}

	@Override
	public List<DownloadFailure> findOpen() {
		return List.of();
	}
}
