package org.nm.gdrive_backup.domain.service;

import java.util.concurrent.atomic.AtomicReference;

import org.nm.gdrive_backup.domain.model.BackupStopMode;
import org.nm.gdrive_backup.domain.port.in.BackupCancellationUseCase;

/**
 * A cooperative stop signal shared by a backup job's services. {@link #begin} resets
 * it, so a later job always starts un-cancelled. Checked between files/pages/drives,
 * never mid-download — an in-flight file always finishes before a stop takes effect.
 */
public class BackupCancellation implements BackupCancellationUseCase {

	private final AtomicReference<BackupStopMode> requestedStop = new AtomicReference<>();

	public void begin() {
		requestedStop.set(null);
	}

	@Override
	public void requestStop(BackupStopMode mode) {
		requestedStop.set(mode);
	}

	public boolean isStopRequested() {
		return requestedStop.get() != null;
	}

	public boolean isImmediateStopRequested() {
		return requestedStop.get() == BackupStopMode.IMMEDIATE;
	}
}
