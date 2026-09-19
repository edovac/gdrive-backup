package org.nm.gdrive_backup.adapter.out.persistence;

import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.nm.gdrive_backup.domain.model.ArchiveManifest;
import org.nm.gdrive_backup.domain.model.ArchiveManifest.ManifestEvent;
import org.nm.gdrive_backup.domain.model.ArchiveManifest.ManifestFile;
import org.nm.gdrive_backup.domain.model.ArchiveManifest.ManifestSource;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.DriveScopeType;
import org.nm.gdrive_backup.domain.model.RevisionMode;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;

/**
 * The manifest.json wire format (see "Manifest format" in the requirements), shared by the archive writer and
 * reader so the two cannot drift. snake_case keys keep the file legible next to the archives table; Gson omits
 * null fields, which is how absent keys are produced and read back as null.
 */
final class ArchiveManifestJson {

	/** Bump when the manifest shape changes incompatibly; readers refuse versions they do not know. */
	static final int FORMAT_VERSION = 1;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private ArchiveManifestJson() {
	}

	static void write(ArchiveManifest manifest, Writer writer) {
		GSON.toJson(toWire(manifest), writer);
	}

	static ArchiveManifest read(String json) throws IOException {
		Wire wire;
		try {
			wire = GSON.fromJson(json, Wire.class);
		} catch (JsonParseException exception) {
			throw new IOException("Unreadable manifest.json: " + exception.getMessage(), exception);
		}
		if (wire == null) {
			throw new IOException("Empty manifest.json");
		}
		if (wire.formatVersion != FORMAT_VERSION) {
			throw new IOException("Unsupported manifest format_version " + wire.formatVersion
					+ " (this version reads " + FORMAT_VERSION + ")");
		}
		try {
			return fromWire(wire);
		} catch (IllegalArgumentException | NullPointerException | DateTimeParseException exception) {
			throw new IOException("Invalid manifest.json: " + exception.getMessage(), exception);
		}
	}

	private static Wire toWire(ArchiveManifest manifest) {
		List<FileWire> files = manifest.files().stream().map(ArchiveManifestJson::toWire).toList();
		List<EventWire> events = manifest.events().stream()
				.map(event -> new EventWire(event.fileId(), event.eventType(), event.oldValue(), event.newValue(),
						event.timestamp().toString()))
				.toList();
		List<SourceWire> sources = manifest.sourceArchives().isEmpty() ? null
				: manifest.sourceArchives().stream()
						.map(source -> new SourceWire(source.sequenceNumber(), source.fileName()))
						.toList();
		return new Wire(FORMAT_VERSION, manifest.scope().key(), manifest.scope().type().name(),
				manifest.mode().name(), manifest.revisionMode().name(), manifest.sequenceNumber(),
				manifest.baseSequenceNumber(), manifest.createdAt().toString(), manifest.fromPageToken(),
				manifest.toPageToken(), sources, files, events);
	}

	/** A removed record carries only its id; every other record always states its trashed flag. */
	private static FileWire toWire(ManifestFile file) {
		if (file.removed()) {
			return new FileWire(file.fileId(), true, null, null, null, null, null, null, null, null, null);
		}
		return new FileWire(file.fileId(), null, file.name(), file.parents(), file.driveId(), file.mimeType(),
				file.trashed(), file.revisionId(), file.entry(), file.sizeBytes(), file.exportMimeType());
	}

	private static ArchiveManifest fromWire(Wire wire) {
		DriveScope scope = new DriveScope(require(wire.scopeKey, "scope_key"),
				DriveScopeType.valueOf(require(wire.scopeType, "scope_type")));
		List<ManifestSource> sources = wire.sourceArchives == null ? List.of()
				: wire.sourceArchives.stream()
						.map(source -> new ManifestSource(source.sequenceNumber, source.fileName))
						.toList();
		List<ManifestFile> files = wire.files == null ? List.of()
				: wire.files.stream().map(ArchiveManifestJson::fromWire).toList();
		List<ManifestEvent> events = wire.events == null ? List.of()
				: wire.events.stream()
						.map(event -> new ManifestEvent(event.fileId, event.eventType, event.oldValue, event.newValue,
								Instant.parse(event.timestamp)))
						.toList();
		return new ArchiveManifest(scope, ArchiveMode.valueOf(require(wire.mode, "mode")),
				RevisionMode.valueOf(require(wire.revisionMode, "revision_mode")), wire.sequenceNumber,
				wire.baseSequenceNumber, Instant.parse(require(wire.createdAt, "created_at")), wire.fromPageToken,
				wire.toPageToken, sources, files, events);
	}

	private static ManifestFile fromWire(FileWire file) {
		String fileId = require(file.fileId, "files[].file_id");
		if (Boolean.TRUE.equals(file.removed)) {
			return ManifestFile.removed(fileId);
		}
		return new ManifestFile(fileId, false, file.name, file.parents == null ? List.of() : file.parents,
				file.driveId, file.mimeType, Boolean.TRUE.equals(file.trashed), file.revisionId, file.entry,
				file.sizeBytes, file.exportMimeType);
	}

	private static String require(String value, String key) {
		if (value == null) {
			throw new IllegalArgumentException("missing " + key);
		}
		return value;
	}

	private record Wire(
			@SerializedName("format_version") int formatVersion,
			@SerializedName("scope_key") String scopeKey,
			@SerializedName("scope_type") String scopeType,
			String mode,
			@SerializedName("revision_mode") String revisionMode,
			@SerializedName("sequence_number") int sequenceNumber,
			@SerializedName("base_sequence_number") Integer baseSequenceNumber,
			@SerializedName("created_at") String createdAt,
			@SerializedName("from_page_token") String fromPageToken,
			@SerializedName("to_page_token") String toPageToken,
			@SerializedName("source_archives") List<SourceWire> sourceArchives,
			List<FileWire> files,
			List<EventWire> events) {
	}

	private record SourceWire(
			@SerializedName("sequence_number") int sequenceNumber,
			@SerializedName("file_name") String fileName) {
	}

	private record FileWire(
			@SerializedName("file_id") String fileId,
			Boolean removed,
			String name,
			List<String> parents,
			@SerializedName("drive_id") String driveId,
			@SerializedName("mime_type") String mimeType,
			Boolean trashed,
			@SerializedName("revision_id") String revisionId,
			String entry,
			@SerializedName("size_bytes") Long sizeBytes,
			@SerializedName("export_mime_type") String exportMimeType) {
	}

	private record EventWire(
			@SerializedName("file_id") String fileId,
			@SerializedName("event_type") String eventType,
			@SerializedName("old_value") String oldValue,
			@SerializedName("new_value") String newValue,
			String timestamp) {
	}
}
