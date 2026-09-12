package org.nm.gdrive_backup.adapter.out.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.nm.gdrive_backup.domain.port.out.VersionStoragePort;

public class LocalVersionStorageAdapter implements VersionStoragePort {

	private final Path backupRoot;

	public LocalVersionStorageAdapter(Path backupRoot) {
		this.backupRoot = backupRoot;
		try {
			Files.createDirectories(backupRoot);
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to create backup root directory", exception);
		}
	}

	@Override
	public Path store(String ownerScope, String fileId, String revisionId, String fileName,
			InputStream content) throws IOException {
		if (content == null) {
			throw new IllegalArgumentException("content must not be null");
		}
		Path targetDirectory = backupRoot
				.resolve(safePathPart(ownerScope, "owner scope"))
				.resolve(safePathPart(fileId, "file id"))
				.resolve(safePathPart(revisionId, "revision id"));
		Files.createDirectories(targetDirectory);
		Path target = targetDirectory.resolve(safeFileName(fileName));
		Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
		return target;
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