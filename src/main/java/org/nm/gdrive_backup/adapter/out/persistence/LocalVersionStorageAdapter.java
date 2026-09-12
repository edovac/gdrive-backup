package org.nm.gdrive_backup.adapter.out.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.nm.gdrive_backup.domain.port.out.VersionStoragePort;
import org.springframework.stereotype.Component;

@Component
public class LocalVersionStorageAdapter implements VersionStoragePort {

	private final Path backupRoot;

	public LocalVersionStorageAdapter() {
		this(resolveBackupRoot());
	}

	LocalVersionStorageAdapter(Path backupRoot) {
		this.backupRoot = backupRoot;
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

	private static Path resolveBackupRoot() {
		String configuredRoot = System.getProperty("gdrive.backup.root");
		if (configuredRoot == null || configuredRoot.isBlank()) {
			configuredRoot = System.getenv("GOOGLE_BACKUP_ROOT");
		}
		return configuredRoot == null || configuredRoot.isBlank()
				? Path.of(System.getProperty("user.home"), ".gdrive-backup", "backupRoot")
				: Path.of(configuredRoot);
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