package org.nm.gdrive_backup.adapter.out.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.nm.gdrive_backup.domain.port.out.ArchiveScanPort;

/** Finds the archive ZIPs under backupRoot/archives by looking at the disk, never at the database. */
public class LocalArchiveScanAdapter implements ArchiveScanPort {

	private final LocalBackupRoot backupRoot;

	public LocalArchiveScanAdapter(LocalBackupRoot backupRoot) {
		this.backupRoot = backupRoot;
	}

	@Override
	public List<String> listArchivePaths() throws IOException {
		Path root = backupRoot.root();
		Path archives = root.resolve("archives");
		if (!Files.isDirectory(archives)) {
			return List.of();
		}
		try (Stream<Path> files = Files.walk(archives)) {
			return files.filter(Files::isRegularFile)
					.filter(path -> isArchive(path.getFileName().toString()))
					.map(path -> root.relativize(path).toString().replace('\\', '/'))
					.sorted()
					.toList();
		}
	}

	/** Staged files start with a dot and end in .tmp, so only finished {@code archive-NNNN-<mode>.zip} files match. */
	private static boolean isArchive(String name) {
		return name.startsWith("archive-") && name.endsWith(".zip");
	}
}
