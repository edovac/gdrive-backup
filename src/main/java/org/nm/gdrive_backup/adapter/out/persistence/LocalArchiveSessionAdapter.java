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
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.nm.gdrive_backup.domain.model.ArchiveManifest;
import org.nm.gdrive_backup.domain.model.ArchiveSession;
import org.nm.gdrive_backup.domain.port.out.ArchiveSessionPort;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

public class LocalArchiveSessionAdapter implements ArchiveSessionPort {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

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
				GSON.toJson(toJson(manifest), writer);
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

	private static ManifestJson toJson(ArchiveManifest manifest) {
		List<ManifestJson.FileJson> files = manifest.files().stream()
				.map(file -> new ManifestJson.FileJson(file.fileId(), file.path(), file.revisionId(), file.sizeBytes()))
				.toList();
		List<ManifestJson.EventJson> events = manifest.events().stream()
				.map(event -> new ManifestJson.EventJson(event.fileId(), event.eventType(), event.oldValue(),
						event.newValue(), event.timestamp().toString()))
				.toList();
		return new ManifestJson(manifest.scopeKey(), manifest.mode().name(), manifest.revisionMode().name(),
				manifest.sequenceNumber(), manifest.baseArchiveId(), manifest.createdAt().toString(),
				manifest.fromPageToken(), manifest.toPageToken(), files, events);
	}

	/** Adapter-local wire format: snake_case keys so manifest.json is legible next to the archives table. */
	private record ManifestJson(
			@SerializedName("scope_key") String scopeKey,
			String mode,
			@SerializedName("revision_mode") String revisionMode,
			@SerializedName("sequence_number") int sequenceNumber,
			@SerializedName("base_archive_id") Long baseArchiveId,
			@SerializedName("created_at") String createdAt,
			@SerializedName("from_page_token") String fromPageToken,
			@SerializedName("to_page_token") String toPageToken,
			List<FileJson> files,
			List<EventJson> events) {

		private record FileJson(
				@SerializedName("file_id") String fileId,
				String path,
				@SerializedName("revision_id") String revisionId,
				@SerializedName("size_bytes") long sizeBytes) {
		}

		private record EventJson(
				@SerializedName("file_id") String fileId,
				@SerializedName("event_type") String eventType,
				@SerializedName("old_value") String oldValue,
				@SerializedName("new_value") String newValue,
				String timestamp) {
		}
	}
}
