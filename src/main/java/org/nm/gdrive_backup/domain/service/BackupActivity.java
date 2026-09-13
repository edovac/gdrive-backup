package org.nm.gdrive_backup.domain.service;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Coordinates backups with backup-location changes: any number of backups may run at
 * once, but the locations can only change while no backup is running.
 */
public class BackupActivity {

	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

	public <T> T duringBackup(Supplier<T> backup) {
		lock.readLock().lock();
		try {
			return backup.get();
		} finally {
			lock.readLock().unlock();
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
