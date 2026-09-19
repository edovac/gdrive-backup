package org.nm.gdrive_backup.adapter.out.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/** The one folder holding backup.db and the archives; switchable at runtime so every adapter follows. */
public class LocalBackupRoot {

	private final AtomicReference<Path> backupRoot;

	public LocalBackupRoot(Path backupRoot) {
		createDirectories(backupRoot);
		this.backupRoot = new AtomicReference<>(backupRoot);
	}

	public Path root() {
		return backupRoot.get();
	}

	/** Writes every later archive under another backup root; archives already written stay where they are. */
	public void switchTo(Path newBackupRoot) {
		createDirectories(newBackupRoot);
		backupRoot.set(newBackupRoot);
	}

	private static void createDirectories(Path directory) {
		try {
			Files.createDirectories(directory);
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to create backup root directory", exception);
		}
	}
}
