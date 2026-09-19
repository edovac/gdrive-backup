package org.nm.gdrive_backup.domain.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.nm.gdrive_backup.domain.model.ArchiveSession;
import org.nm.gdrive_backup.domain.model.DriveExportLimitException;
import org.nm.gdrive_backup.domain.model.FileCapture;
import org.nm.gdrive_backup.domain.model.StreamedFile;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.port.out.DriveContentPort;

/** Streams a file's content from Drive straight into the archive session being staged. */
public class FileContentStreamingService {

	private static final Map<String, ExportFormat> EXPORT_FORMATS = Map.of(
			"application/vnd.google-apps.document",
				new ExportFormat("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx"),
			"application/vnd.google-apps.spreadsheet",
				new ExportFormat("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx"),
			"application/vnd.google-apps.presentation",
				new ExportFormat("application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx"));
	private static final String NATIVE_MIME_PREFIX = "application/vnd.google-apps.";
	private static final ExportFormat PDF_FALLBACK = new ExportFormat("application/pdf", ".pdf");

	private final DriveContentPort contentPort;

	public FileContentStreamingService(DriveContentPort contentPort) {
		this.contentPort = contentPort;
	}

	/** Returns an uncommitted capture (no id, no archive id) naming the entry that now holds the content. */
	public StreamedFile stream(ServiceAccountAccess access, StoredFile file, ArchiveSession session, String entryName) {
		if (file == null || file.fileId() == null || file.fileId().isBlank()) {
			throw new IllegalArgumentException("file with an id is required");
		}
		if (file.headRevisionId() == null || file.headRevisionId().isBlank()) {
			throw new IllegalArgumentException("file with a revision id is required");
		}
		ExportFormat exportFormat = EXPORT_FORMATS.get(file.mimeType());
		try {
			return streamContent(access, file, exportFormat, session, entryName);
		} catch (DriveExportLimitException exception) {
			if (exportFormat == null) {
				throw new IllegalStateException("Unexpected export limit for a non-native Drive file", exception);
			}
			String fallbackEntry = pdfEntryName(entryName, exportFormat.extension(), session);
			try {
				return streamContent(access, file, PDF_FALLBACK, session, fallbackEntry);
			} catch (DriveExportLimitException fallbackException) {
				throw new IllegalStateException("Google PDF fallback also exceeded the export limit", fallbackException);
			} catch (IOException fallbackException) {
				throw new IllegalStateException("Unable to back up file content using PDF fallback", fallbackException);
			}
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to back up file content", exception);
		}
	}

	private StreamedFile streamContent(ServiceAccountAccess access, StoredFile file, ExportFormat exportFormat,
			ArchiveSession session, String entryName) throws IOException {
		// The stream is opened before the entry so an export-limit failure leaves nothing half-written.
		try (InputStream content = exportFormat == null
				? contentPort.download(access, file.fileId())
				: contentPort.export(access, file.fileId(), exportFormat.mimeType())) {
			long size = session.writeEntry(entryName, content);
			FileCapture capture = new FileCapture(null, file.fileId(), file.headRevisionId(), Instant.now(), null,
					entryName, size);
			return new StreamedFile(capture, exportFormat == null ? null : exportFormat.mimeType());
		}
	}

	/**
	 * Whether the file has content this service can put in an archive: a content revision to record, and either
	 * ordinary bytes or a native type Drive can export. Folders, shortcuts, Forms and other native types with
	 * no export are recorded as metadata only.
	 */
	static boolean hasBackableContent(StoredFile file) {
		if (file.headRevisionId() == null || file.headRevisionId().isBlank()) {
			return false;
		}
		String mimeType = file.mimeType();
		return mimeType == null || !mimeType.startsWith(NATIVE_MIME_PREFIX) || EXPORT_FORMATS.containsKey(mimeType);
	}

	/** The extension Drive's export adds to a native file's name, if the mime type is exportable. */
	static Optional<String> exportExtensionFor(String mimeType) {
		return Optional.ofNullable(EXPORT_FORMATS.get(mimeType)).map(ExportFormat::extension);
	}

	private static String pdfEntryName(String entryName, String exportExtension, ArchiveSession session) {
		String base = entryName.endsWith(exportExtension)
				? entryName.substring(0, entryName.length() - exportExtension.length())
				: entryName;
		String extension = entryName.endsWith(exportExtension) ? ".pdf" : "";
		String candidate = base + extension;
		int suffix = 2;
		while (session.containsEntry(candidate)) {
			candidate = base + " (" + suffix + ")" + extension;
			suffix++;
		}
		return candidate;
	}

	private record ExportFormat(String mimeType, String extension) {
	}
}
