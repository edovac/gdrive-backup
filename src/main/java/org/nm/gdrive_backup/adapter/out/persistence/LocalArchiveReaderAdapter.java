package org.nm.gdrive_backup.adapter.out.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import org.nm.gdrive_backup.domain.model.ArchiveManifest;
import org.nm.gdrive_backup.domain.model.ArchiveReader;
import org.nm.gdrive_backup.domain.port.out.ArchiveReaderPort;

public class LocalArchiveReaderAdapter implements ArchiveReaderPort {

	private static final String MANIFEST_ENTRY = "manifest.json";

	private final LocalBackupRoot backupRoot;

	public LocalArchiveReaderAdapter(LocalBackupRoot backupRoot) {
		this.backupRoot = backupRoot;
	}

	@Override
	public ArchiveReader open(String relativePath) throws IOException {
		Path path = backupRoot.root().resolve(relativePath);
		if (!Files.isRegularFile(path)) {
			throw new NoSuchFileException(path.toString());
		}
		ZipFile zip;
		try {
			zip = new ZipFile(path.toFile());
		} catch (ZipException exception) {
			throw new IOException("Not a readable archive: " + path + " (" + exception.getMessage() + ")", exception);
		}
		try {
			ZipEntry manifestEntry = zip.getEntry(MANIFEST_ENTRY);
			if (manifestEntry == null) {
				throw new IOException("Archive has no " + MANIFEST_ENTRY + ": " + path);
			}
			ArchiveManifest manifest;
			try (InputStream stream = zip.getInputStream(manifestEntry)) {
				manifest = ArchiveManifestJson.read(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
			}
			return new Reader(zip, manifest, path);
		} catch (IOException | RuntimeException exception) {
			zip.close();
			throw exception;
		}
	}

	private static final class Reader implements ArchiveReader {

		private final ZipFile zip;
		private final ArchiveManifest manifest;
		private final Path path;

		Reader(ZipFile zip, ArchiveManifest manifest, Path path) {
			this.zip = zip;
			this.manifest = manifest;
			this.path = path;
		}

		@Override
		public ArchiveManifest manifest() {
			return manifest;
		}

		@Override
		public InputStream openEntry(String entryName) throws IOException {
			ZipEntry entry = zip.getEntry(entryName);
			if (entry == null) {
				throw new IOException("Archive " + path + " has no entry " + entryName);
			}
			return zip.getInputStream(entry);
		}

		@Override
		public void close() {
			try {
				zip.close();
			} catch (IOException ignored) {
				// Nothing useful to do when closing a read-only archive fails.
			}
		}
	}
}
