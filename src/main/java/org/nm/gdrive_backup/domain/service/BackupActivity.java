package org.nm.gdrive_backup.domain.service;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Coordinates backups with backup-location changes and with exclusive archive operations. Any number of backups
 * may run at once, but the locations can only change while nothing runs, and an exclusive operation (a merge or a
 * deletion of archives) runs alone: it refuses to start beside a backup or another operation, and backups refuse
 * to start beside it. Progress reporting and cancellation are shared, so only one such job can be reported at a
 * time.
 */
public class BackupActivity {

	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	private boolean exclusiveRunning;

	public <T> T duringBackup(Supplier<T> backup) {
		synchronized (this) {
			if (exclusiveRunning) {
				throw new IllegalStateException("An archive operation is running; wait for it to finish");
			}
			lock.readLock().lock();
		}
		try {
			return backup.get();
		} finally {
			lock.readLock().unlock();
		}
	}

	/** Runs {@code operation} alone, or throws at once if a backup or another operation is running. */
	public <T> T duringExclusiveOperation(Supplier<T> operation) {
		synchronized (this) {
			if (exclusiveRunning || lock.getReadLockCount() > 0) {
				throw new IllegalStateException("A backup or archive operation is already running");
			}
			exclusiveRunning = true;
			lock.readLock().lock();
		}
		try {
			return operation.get();
		} finally {
			lock.readLock().unlock();
			synchronized (this) {
				exclusiveRunning = false;
			}
		}
	}

	public void changeLocations(Runnable change) {
		if (!lock.writeLock().tryLock()) {
			throw new IllegalStateException("Backup locations can't change while a backup is running");
		}
		try {
			change.run();
		} finally {
			lock.writeLock().unlock();
		}
	}

	public boolean isActive() {
		return lock.getReadLockCount() > 0;
	}
}
