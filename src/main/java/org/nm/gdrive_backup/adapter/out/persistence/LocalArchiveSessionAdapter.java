package org.nm.gdrive_backup.adapter.out.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.nm.gdrive_backup.domain.model.ArchiveManifest;
import org.nm.gdrive_backup.domain.model.ArchiveSession;
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
			return new Session(temp, target, new ZipOutputStream(Files.newOutputStream(temp)));
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
