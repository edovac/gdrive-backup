package org.nm.gdrive_backup.domain.port.in;

import org.nm.gdrive_backup.domain.model.BackupStopMode;

public interface BackupCancellationUseCase {

	void requestStop(BackupStopMode mode);
}
