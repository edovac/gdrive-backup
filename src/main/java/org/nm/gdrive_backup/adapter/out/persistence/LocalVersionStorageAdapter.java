package org.nm.gdrive_backup.adapter.out.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicReference;

import org.nm.gdrive_backup.domain.port.out.VersionStoragePort;

public class LocalVersionStorageAdapter implements VersionStoragePort {

	private final AtomicReference<Path> backupRoot;

	public LocalVersionStorageAdapter(Path backupRoot) {
		createDirectories(backupRoot);
		this.backupRoot = new AtomicReference<>(backupRoot);
	}

	public Path root() {
		return backupRoot.get();
	}

	/** Writes every later version under another backup root; versions already stored stay where they are. */
	public void switchTo(Path newBackupRoot) {
		createDirectories(newBackupRoot);
		backupRoot.set(newBackupRoot);
	}

	@Override
	public Path store(String ownerScope, String fileId, String revisionId, String fileName,
			InputStream content) throws IOException {
		if (content == null) {
			throw new IllegalArgumentException("content must not be null");
		}
		Path targetDirectory = backupRoot.get()
				.resolve(safePathPart(ownerScope, "owner scope"))
				.resolve(safePathPart(fileId, "file id"))
				.resolve(safePathPart(revisionId, "revision id"));
		Files.createDirectories(targetDirectory);
		Path target = targetDirectory.resolve(safeFileName(fileName));
		Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
		return target;
	}

	private static void createDirectories(Path directory) {
		try {
			Files.createDirectories(directory);
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to create backup root directory", exception);
		}
	}

	private static String safePathPart(String value, String label) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(label + " must not be blank");
		}
		return value.replaceAll("[^a-zA-Z0-9._@-]", "_");
	}

	private static String safeFileName(String value) {
		String fileName = safePathPart(value, "file name");
		return fileName.equals(".") || fileName.equals("..") ? "unnamed-file" : fileName;
	}
}
