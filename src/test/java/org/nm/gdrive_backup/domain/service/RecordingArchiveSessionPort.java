package org.nm.gdrive_backup.domain.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import org.nm.gdrive_backup.domain.model.ArchiveManifest;
import org.nm.gdrive_backup.domain.model.ArchiveSession;
import org.nm.gdrive_backup.domain.model.StagedContent;
import org.nm.gdrive_backup.domain.port.out.ArchiveSessionPort;

/** In-memory archive staging that records what was written and in which order relative to the shared log. */
class RecordingArchiveSessionPort implements ArchiveSessionPort {

	final List<String> log;
	final List<String> openedPaths = new ArrayList<>();
	final Map<String, byte[]> entries = new LinkedHashMap<>();
	/** Whether each staged entry was written compressed; entries written from a stream are not listed. */
	final Map<String, Boolean> compressedByEntry = new LinkedHashMap<>();
	/** Everything {@code stage} produced, in staging order; staging runs on download threads, so this is synchronized. */
	final List<RecordingStaged> staged = Collections.synchronizedList(new ArrayList<>());
	ArchiveManifest publishedManifest;
	boolean discarded;

	RecordingArchiveSessionPort(List<String> log) {
		this.log = log;
	}

	/** True once every piece of staged content was written to an entry or otherwise released. */
	boolean allStagedReleased() {
		synchronized (staged) {
			return staged.stream().allMatch(content -> content.closed);
		}
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
			public StagedContent stage(InputStream content) throws IOException {
				RecordingStaged recorded = new RecordingStaged(content.readAllBytes());
				staged.add(recorded);
				return recorded;
			}

			@Override
			public long writeEntry(String entryName, StagedContent content, boolean compress) {
				RecordingStaged recorded = (RecordingStaged) content;
				entries.put(entryName, recorded.bytes);
				compressedByEntry.put(entryName, compress);
				recorded.close();
				return recorded.bytes.length;
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

	static final class RecordingStaged implements StagedContent {

		final byte[] bytes;
		volatile boolean closed;

		RecordingStaged(byte[] bytes) {
			this.bytes = bytes;
		}

		@Override
		public long size() {
			return bytes.length;
		}

		@Override
		public long crc32() {
			CRC32 crc = new CRC32();
			crc.update(bytes);
			return crc.getValue();
		}

		@Override
		public void close() {
			closed = true;
		}
	}
}
