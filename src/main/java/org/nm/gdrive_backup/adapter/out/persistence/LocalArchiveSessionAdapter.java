package org.nm.gdrive_backup.adapter.out.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.nm.gdrive_backup.domain.model.ArchiveManifest;
import org.nm.gdrive_backup.domain.model.ArchiveSession;
import org.nm.gdrive_backup.domain.model.StagedContent;
import org.nm.gdrive_backup.domain.port.out.ArchiveSessionPort;


public class LocalArchiveSessionAdapter implements ArchiveSessionPort {

	private final LocalBackupRoot backupRoot;

	public LocalArchiveSessionAdapter(LocalBackupRoot backupRoot) {
		this.backupRoot = backupRoot;
	}

	@Override
	public ArchiveSession open(String relativeTargetPath) throws IOException {
		Path target = backupRoot.root().resolve(relativeTargetPath);
		Files.createDirectories(target.getParent());
		// Staged next to the target so the final move is a same-filesystem rename; a partial file is
		// never visible at the target path.
		Path temp = Files.createTempFile(target.getParent(), ".archive-", ".zip.tmp");
		try {
			ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(temp));
				// Entries that do compress are mostly text, where a faster level costs little size; content that
				// is already compressed is stored as-is (see writeEntry with compress = false).
				output.setLevel(Deflater.BEST_SPEED);
				return new Session(temp, target, output);
		} catch (IOException exception) {
			Files.deleteIfExists(temp);
			throw exception;
		}
	}

	private static final class Session implements ArchiveSession {

		private final Path temp;
		private final Path target;
		private final ZipOutputStream output;
		private final Set<String> entryNames = new HashSet<>();
		// Spool files not yet written or released; staging runs on download threads, so this set is concurrent.
		private final Set<Path> spools = ConcurrentHashMap.newKeySet();
		private boolean finished;

		Session(Path temp, Path target, ZipOutputStream output) {
			this.temp = temp;
			this.target = target;
			this.output = output;
		}

		@Override
		public long writeEntry(String entryName, InputStream content) throws IOException {
			output.putNextEntry(new ZipEntry(entryName));
			long size = content.transferTo(output);
			output.closeEntry();
			entryNames.add(entryName);
			return size;
		}

		@Override
		public StagedContent stage(InputStream content) throws IOException {
			// Next to the archive being staged, so spools follow the backup root and share its cleanup.
			Path spool = Files.createTempFile(target.getParent(), ".spool-", ".tmp");
			spools.add(spool);
			try {
				CRC32 crc = new CRC32();
				long size;
				try (OutputStream out = new CheckedOutputStream(Files.newOutputStream(spool), crc)) {
					size = content.transferTo(out);
				}
				return new Spool(spool, size, crc.getValue());
			} catch (IOException | RuntimeException exception) {
				release(spool);
				throw exception;
			}
		}

		@Override
		public long writeEntry(String entryName, StagedContent staged, boolean compress) throws IOException {
			if (!(staged instanceof Spool spool) || !spools.contains(spool.path)) {
				throw new IllegalArgumentException("staged content does not belong to this session");
			}
			ZipEntry entry = new ZipEntry(entryName);
			if (!compress) {
				// ZipOutputStream cannot defer these for a stored entry, so they must be known before the header.
				entry.setMethod(ZipEntry.STORED);
				entry.setSize(spool.size);
				entry.setCompressedSize(spool.size);
				entry.setCrc(spool.crc32);
			}
			output.putNextEntry(entry);
			Files.copy(spool.path, output);
			output.closeEntry();
			entryNames.add(entryName);
			spool.close();
			return spool.size;
		}

		@Override
		public boolean containsEntry(String entryName) {
			return entryNames.contains(entryName);
		}

		@Override
		public void publish(ArchiveManifest manifest) throws IOException {
			try {
				output.putNextEntry(new ZipEntry("manifest.json"));
				Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
				ArchiveManifestJson.write(manifest, writer);
				writer.flush();
				output.closeEntry();
				output.close();
				move(temp, target);
				finished = true;
			} catch (IOException exception) {
				discard();
				throw exception;
			}
		}

		@Override
		public void discard() {
			if (finished) {
				return;
			}
			finished = true;
			try {
				output.close();
			} catch (IOException ignored) {
				// The temp file is deleted next; a failed close of a stream being thrown away changes nothing.
			}
			try {
				Files.deleteIfExists(temp);
			} catch (IOException ignored) {
				// Best effort: a leftover .zip.tmp is never mistaken for an archive.
			}
			spools.forEach(this::release);
		}

		private void release(Path spool) {
			spools.remove(spool);
			try {
				Files.deleteIfExists(spool);
			} catch (IOException ignored) {
				// Best effort: a leftover .spool-*.tmp is never mistaken for an archive.
			}
		}

		private final class Spool implements StagedContent {

			private final Path path;
			private final long size;
			private final long crc32;

			Spool(Path path, long size, long crc32) {
				this.path = path;
				this.size = size;
				this.crc32 = crc32;
			}

			@Override
			public long size() {
				return size;
			}

			@Override
			public long crc32() {
				return crc32;
			}

			@Override
			public void close() {
				release(path);
			}
		}

		@Override
		public void close() {
			discard();
		}

		private static void move(Path temp, Path target) throws IOException {
			try {
				Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException exception) {
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}
}
