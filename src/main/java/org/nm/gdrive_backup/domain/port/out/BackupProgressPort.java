package org.nm.gdrive_backup.domain.port.out;

import java.util.Optional;

import org.nm.gdrive_backup.domain.model.BackupProgress;

public interface BackupProgressPort {

	void report(BackupProgress progress);

	Optional<BackupProgress> latest();
}
