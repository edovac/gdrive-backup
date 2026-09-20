package org.nm.gdrive_backup.adapter.out.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

import org.nm.gdrive_backup.domain.port.out.ArchiveStoragePort;

public class LocalArchiveStorageAdapter implements ArchiveStoragePort {

	private static final String ARCHIVES_DIRECTORY = "archives";

	private final LocalBackupRoot backupRoot;

	public LocalArchiveStorageAdapter(LocalBackupRoot backupRoot) {
		this.backupRoot = backupRoot;
	}

	@Override
	public OptionalLong sizeOf(String relativePath) {
		try {
			Path path = archivePath(relativePath);
			return Files.isRegularFile(path) ? OptionalLong.of(Files.size(path)) : OptionalLong.empty();
		} catch (IOException | IllegalArgumentException exception) {
			return OptionalLong.empty();
		}
	}

	@Override
	public void delete(String relativePath) throws IOException {
		Files.deleteIfExists(archivePath(relativePath));
	}

	/** Resolves against the current root and refuses anything that escapes the archives directory. */
	private Path archivePath(String relativePath) {
		Path root = backupRoot.root().toAbsolutePath().normalize();
		Path archivesRoot = root.resolve(ARCHIVES_DIRECTORY).normalize();
		Path path = root.resolve(relativePath).normalize();
		if (!path.startsWith(archivesRoot) || path.equals(archivesRoot)) {
			throw new IllegalArgumentException("Not an archive path: " + relativePath);
		}
		return path;
	}
}
