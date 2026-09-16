package org.nm.gdrive_backup.adapter.out.persistence;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.nm.gdrive_backup.domain.model.ArchiveEntry;
import org.nm.gdrive_backup.domain.model.ArchiveManifest;
import org.nm.gdrive_backup.domain.port.out.ArchiveWriterPort;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

public class LocalArchiveWriterAdapter implements ArchiveWriterPort {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final LocalCaptureStorageAdapter captureStorage;

	public LocalArchiveWriterAdapter(LocalCaptureStorageAdapter captureStorage) {
		this.captureStorage = captureStorage;
	}

	@Override
	public void write(String relativeTargetPath, List<ArchiveEntry> entries, ArchiveManifest manifest)
			throws IOException {
		Path root = captureStorage.root();
		Path target = root.resolve(relativeTargetPath);
		Files.createDirectories(target.getParent());
		Path temp = Files.createTempFile(target.getParent(), ".archive-", ".zip.tmp");
		try {
			writeZip(temp, root, entries, manifest);
			publish(temp, target);
		} catch (IOException exception) {
			Files.deleteIfExists(temp);
			throw exception;
		}
	}

	private static void writeZip(Path temp, Path root, List<ArchiveEntry> entries, ArchiveManifest manifest)
			throws IOException {
		try (var output = new ZipOutputStream(Files.newOutputStream(temp))) {
			for (ArchiveEntry entry : entries) {
				output.putNextEntry(new ZipEntry(entry.entryName()));
				Files.copy(root.resolve(entry.sourceRelativePath()), output);
				output.closeEntry();
			}
			output.putNextEntry(new ZipEntry("manifest.json"));
			writeManifest(output, manifest);
			output.closeEntry();
		}
	}

	private static void writeManifest(ZipOutputStream output, ArchiveManifest manifest) throws IOException {
		Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
		GSON.toJson(toJson(manifest), writer);
		writer.flush();
	}

	private static void publish(Path temp, Path target) throws IOException {
		try {
			Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException exception) {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
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
