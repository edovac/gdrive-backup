package org.nm.gdrive_backup.domain.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.nm.gdrive_backup.domain.model.ArchiveManifest;
import org.nm.gdrive_backup.domain.model.ArchiveSession;
import org.nm.gdrive_backup.domain.port.out.ArchiveSessionPort;

/** In-memory archive staging that records what was written and in which order relative to the shared log. */
class RecordingArchiveSessionPort implements ArchiveSessionPort {

	final List<String> log;
	final List<String> openedPaths = new ArrayList<>();
	final Map<String, byte[]> entries = new LinkedHashMap<>();
	ArchiveManifest publishedManifest;
	boolean discarded;

	RecordingArchiveSessionPort(List<String> log) {
		this.log = log;
	}

	@Override
	public ArchiveSession open(String relativeTargetPath) {
		openedPaths.add(relativeTargetPath);
		log.add("open");
		return new ArchiveSession() {
			private boolean published;

			@Override
			public long writeEntry(String entryName, InputStream content) throws IOException {
				byte[] bytes = content.readAllBytes();
				entries.put(entryName, bytes);
				return bytes.length;
			}

			@Override
			public boolean containsEntry(String entryName) {
				return entries.containsKey(entryName);
			}

			@Override
			public void publish(ArchiveManifest manifest) {
				publishedManifest = manifest;
				published = true;
				log.add("publish");
			}

			@Override
			public void discard() {
				if (!published && !discarded) {
					discarded = true;
					log.add("discard");
				}
			}

			@Override
			public void close() {
				discard();
			}
		};
	}
}
