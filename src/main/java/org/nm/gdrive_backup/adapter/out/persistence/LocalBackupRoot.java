package org.nm.gdrive_backup.adapter.out.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The one folder holding backup.db and the archives; switchable at runtime so every adapter follows. */
public class LocalBackupRoot {

	private static final Logger LOGGER = LoggerFactory.getLogger(LocalBackupRoot.class);

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

	/**
	 * Deletes the temp files an archive session leaves behind when the process dies mid-run: the half-written
	 * {@code .archive-*.zip.tmp} and the {@code .spool-*.tmp} copies of downloaded content, which can be as large as
	 * the biggest file. Only safe while no backup runs, so it is called at startup and from a location change, both of
	 * which hold the exclusive side of {@code BackupActivity}. Returns how many files were removed.
	 */
	public int sweepLeftovers() {
		Path archives = root().resolve("archives");
		if (!Files.isDirectory(archives)) {
			return 0;
		}
		int removed = 0;
		try (Stream<Path> files = Files.walk(archives)) {
			for (Path file : (Iterable<Path>) files.filter(LocalBackupRoot::isLeftover)::iterator) {
				try {
					Files.deleteIfExists(file);
					removed++;
				} catch (IOException exception) {
					LOGGER.warn("Unable to delete leftover temp file {}", file, exception);
				}
			}
		} catch (IOException | UncheckedIOException exception) {
			LOGGER.warn("Unable to look for leftover temp files under {}", archives, exception);
		}
		if (removed > 0) {
			LOGGER.info("Removed {} leftover temp file(s) from an interrupted backup under {}", removed, archives);
		}
		return removed;
	}

	private static boolean isLeftover(Path path) {
		String name = path.getFileName().toString();
		return Files.isRegularFile(path)
				&& (name.startsWith(".archive-") && name.endsWith(".zip.tmp")
						|| name.startsWith(".spool-") && name.endsWith(".tmp"));
	}

	private static void createDirectories(Path directory) {
		try {
			Files.createDirectories(directory);
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to create backup root directory", exception);
		}
	}
}
