package org.nm.gdrive_backup.domain.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.util.HashMap;
import java.util.Map;

import org.nm.gdrive_backup.domain.model.ArchiveManifest;
import org.nm.gdrive_backup.domain.model.ArchiveReader;
import org.nm.gdrive_backup.domain.port.out.ArchiveReaderPort;

/** In-memory archives keyed by relative path; a path that was never added behaves like a missing file. */
class FakeArchiveReaderPort implements ArchiveReaderPort {

	private final Map<String, ArchiveManifest> manifests = new HashMap<>();
	private final Map<String, Map<String, byte[]>> entries = new HashMap<>();

	void add(String path, ArchiveManifest manifest, Map<String, String> entryContents) {
		manifests.put(path, manifest);
		Map<String, byte[]> bytes = new HashMap<>();
		entryContents.forEach((name, content) -> bytes.put(name, content.getBytes()));
		entries.put(path, bytes);
	}

	@Override
	public ArchiveReader open(String relativePath) throws IOException {
		ArchiveManifest manifest = manifests.get(relativePath);
		if (manifest == null) {
			throw new NoSuchFileException(relativePath);
		}
		Map<String, byte[]> archiveEntries = entries.get(relativePath);
		return new ArchiveReader() {
			@Override
			public ArchiveManifest manifest() {
				return manifest;
			}

			@Override
			public InputStream openEntry(String entryName) throws IOException {
				byte[] bytes = archiveEntries.get(entryName);
				if (bytes == null) {
					throw new IOException("Archive " + relativePath + " has no entry " + entryName);
				}
				return new ByteArrayInputStream(bytes);
			}

			@Override
			public void close() {
			}
		};
	}
}
