package org.nm.gdrive_backup.adapter.out.progress;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.nm.gdrive_backup.domain.model.BackupProgress;
import org.nm.gdrive_backup.domain.port.out.BackupProgressPort;
import org.springframework.stereotype.Component;

/** Keeps only the newest progress snapshot; the UI polls it instead of subscribing. */
@Component
public class LatestBackupProgressAdapter implements BackupProgressPort {

	private final AtomicReference<BackupProgress> latest = new AtomicReference<>();

	@Override
	public void report(BackupProgress progress) {
		latest.set(progress);
	}

	@Override
	public Optional<BackupProgress> latest() {
		return Optional.ofNullable(latest.get());
	}
}
