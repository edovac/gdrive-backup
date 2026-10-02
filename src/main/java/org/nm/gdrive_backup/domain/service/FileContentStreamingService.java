package org.nm.gdrive_backup.domain.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongConsumer;

import org.nm.gdrive_backup.domain.model.ArchiveSession;
import org.nm.gdrive_backup.domain.model.DriveExportLimitException;
import org.nm.gdrive_backup.domain.model.FetchedFile;
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
	private static final Set<String> ALREADY_COMPRESSED_TYPES = Set.of(
			"application/pdf", "application/zip", "application/x-zip-compressed", "application/gzip",
			"application/x-gzip", "application/x-bzip2", "application/x-7z-compressed", "application/vnd.rar",
			"application/x-rar-compressed", "application/java-archive",
			"image/jpeg", "image/png", "image/gif", "image/webp", "image/heic", "image/heif", "image/avif");
	private static final Set<String> UNCOMPRESSED_AUDIO_TYPES = Set.of(
			"audio/wav", "audio/x-wav", "audio/wave", "audio/aiff", "audio/x-aiff");

	/** How many times a file is started when its stream fails part-way, the first try included. */
	private static final int MAX_STREAM_ATTEMPTS = 3;

	private final DriveContentPort contentPort;

	public FileContentStreamingService(DriveContentPort contentPort) {
		this.contentPort = contentPort;
	}

	/** Returns an uncommitted capture (no id, no archive id) naming the entry that now holds the content. */
	public StreamedFile stream(ServiceAccountAccess access, StoredFile file, ArchiveSession session, String entryName) {
		return write(file, fetch(access, file, session), session, entryName);
	}

	/**
	 * Downloads (or exports) the file and stages its bytes in the session, without choosing an entry name or
	 * touching the archive. Safe to run on several threads at once; {@link #write} then appends the result.
	 */
	public FetchedFile fetch(ServiceAccountAccess access, StoredFile file, ArchiveSession session) {
		return fetch(access, file, session, bytes -> {
		});
	}

	/**
	 * As {@link #fetch(ServiceAccountAccess, StoredFile, ArchiveSession)}, also telling {@code onBytesDownloaded} how
	 * many bytes have arrived so far while the content streams in (a few times a second, and once at the end). The
	 * listener runs on the downloading thread. A PDF fallback starts counting again from zero.
	 */
	public FetchedFile fetch(ServiceAccountAccess access, StoredFile file, ArchiveSession session,
			LongConsumer onBytesDownloaded) {
		if (file == null || file.fileId() == null || file.fileId().isBlank()) {
			throw new IllegalArgumentException("file with an id is required");
		}
		if (file.headRevisionId() == null || file.headRevisionId().isBlank()) {
			throw new IllegalArgumentException("file with a revision id is required");
		}
		ExportFormat exportFormat = EXPORT_FORMATS.get(file.mimeType());
		try {
			return fetchContent(access, file, exportFormat, session, null, onBytesDownloaded);
		} catch (DriveExportLimitException exception) {
			if (exportFormat == null) {
				throw new IllegalStateException("Unexpected export limit for a non-native Drive file", exception);
			}
			try {
				return fetchContent(access, file, PDF_FALLBACK, session, exportFormat.extension(), onBytesDownloaded);
			} catch (DriveExportLimitException fallbackException) {
				throw new IllegalStateException("Google PDF fallback also exceeded the export limit", fallbackException);
			} catch (IOException fallbackException) {
				throw new IllegalStateException("Unable to back up file content using PDF fallback", fallbackException);
			}
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to back up file content", exception);
		}
	}

	/**
	 * Appends fetched content to the archive as {@code entryName} and releases it. Must run on the single thread
	 * that writes the archive: a PDF fallback renames the entry against the names already taken.
	 */
	public StreamedFile write(StoredFile file, FetchedFile fetched, ArchiveSession session, String entryName) {
		return write(file, fetched, session, entryName, Set.of());
	}

	/**
	 * As {@link #write(StoredFile, FetchedFile, ArchiveSession, String)}, for a run that writes its files in no
	 * particular order. {@code reservedEntryNames} are the names the other files are going to take: a PDF fallback
	 * avoids them as well as the names already written, so it can never claim the name of a file written later.
	 */
	public StreamedFile write(StoredFile file, FetchedFile fetched, ArchiveSession session, String entryName,
			Set<String> reservedEntryNames) {
		try (fetched) {
			String finalName = fetched.fallbackFromExtension() == null
					? entryName
					: pdfEntryName(entryName, fetched.fallbackFromExtension(), session, reservedEntryNames);
			String contentMimeType = fetched.exportMimeType() == null ? file.mimeType() : fetched.exportMimeType();
			long size = session.writeEntry(finalName, fetched.content(), !isAlreadyCompressed(contentMimeType));
			FileCapture capture = new FileCapture(null, file.fileId(), file.headRevisionId(), Instant.now(), null,
					finalName, size);
			return new StreamedFile(capture, fetched.exportMimeType());
		} catch (IOException exception) {
			throw new IllegalStateException(fetched.fallbackFromExtension() == null
					? "Unable to back up file content"
					: "Unable to back up file content using PDF fallback", exception);
		}
	}

	private FetchedFile fetchContent(ServiceAccountAccess access, StoredFile file, ExportFormat exportFormat,
			ArchiveSession session, String fallbackFromExtension, LongConsumer onBytesDownloaded) throws IOException {
		for (int attempt = 1;; attempt++) {
			// The stream is opened before anything is staged so an export-limit failure leaves nothing behind.
			try (InputStream content = exportFormat == null
					? contentPort.download(access, file.fileId())
					: contentPort.export(access, file.fileId(), exportFormat.mimeType())) {
				return new FetchedFile(session.stage(new ReportingInputStream(content, onBytesDownloaded)),
						exportFormat == null ? null : exportFormat.mimeType(), fallbackFromExtension);
			} catch (IOException exception) {
				// A connection that drops part-way through a large file is routine; the port only retries opening the
				// stream, so start the file again rather than lose the whole run. A failed stage has already released its spool.
				if (attempt >= MAX_STREAM_ATTEMPTS || exception instanceof DriveExportLimitException
						|| exception instanceof InterruptedIOException || Thread.currentThread().isInterrupted()) {
					throw exception;
				}
			}
		}
	}

	/**
	 * Whether a file of this type is already in a compressed container, so deflating it again costs CPU for
	 * almost no size gain: Office and OpenDocument files are ZIPs, and PDF, archives, JPEG/PNG-style images,
	 * video and compressed audio are compressed by their own format.
	 */
	static boolean isAlreadyCompressed(String mimeType) {
		if (mimeType == null) {
			return false;
		}
		return ALREADY_COMPRESSED_TYPES.contains(mimeType)
				|| mimeType.startsWith("application/vnd.openxmlformats-officedocument.")
				|| mimeType.startsWith("application/vnd.oasis.opendocument.")
				|| mimeType.startsWith("video/")
				|| mimeType.startsWith("audio/") && !UNCOMPRESSED_AUDIO_TYPES.contains(mimeType);
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

	/** The extension a file exported as {@code exportMimeType} carries, or empty for a format we never export. */
	static Optional<String> extensionForExportMimeType(String exportMimeType) {
		if (exportMimeType == null) {
			return Optional.empty();
		}
		if (PDF_FALLBACK.mimeType().equals(exportMimeType)) {
			return Optional.of(PDF_FALLBACK.extension());
		}
		return EXPORT_FORMATS.values().stream()
				.filter(format -> format.mimeType().equals(exportMimeType))
				.map(ExportFormat::extension)
				.findFirst();
	}

	private static String pdfEntryName(String entryName, String exportExtension, ArchiveSession session,
			Set<String> reservedEntryNames) {
		String base = entryName.endsWith(exportExtension)
				? entryName.substring(0, entryName.length() - exportExtension.length())
				: entryName;
		String extension = entryName.endsWith(exportExtension) ? ".pdf" : "";
		String candidate = base + extension;
		int suffix = 2;
		while (session.containsEntry(candidate) || reservedEntryNames.contains(candidate)) {
			candidate = base + " (" + suffix + ")" + extension;
			suffix++;
		}
		return candidate;
	}

	private record ExportFormat(String mimeType, String extension) {
	}
}
