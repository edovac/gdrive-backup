package org.nm.gdrive_backup.domain.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.nm.gdrive_backup.domain.model.FileVersion;
import org.nm.gdrive_backup.domain.model.DriveExportLimitException;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.port.out.DriveContentPort;
import org.nm.gdrive_backup.domain.port.out.FileVersionPort;
import org.nm.gdrive_backup.domain.port.out.VersionStoragePort;

public class FileContentBackupService {

	private static final Map<String, ExportFormat> EXPORT_FORMATS = Map.of(
			"application/vnd.google-apps.document",
				new ExportFormat("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx"),
			"application/vnd.google-apps.spreadsheet",
				new ExportFormat("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx"),
			"application/vnd.google-apps.presentation",
				new ExportFormat("application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx"));

	private final DriveContentPort contentPort;
	private final VersionStoragePort storagePort;
	private final FileVersionPort versionPort;

	public FileContentBackupService(DriveContentPort contentPort, VersionStoragePort storagePort,
			FileVersionPort versionPort) {
		this.contentPort = contentPort;
		this.storagePort = storagePort;
		this.versionPort = versionPort;
	}

	public FileVersion backup(ServiceAccountAccess access, StoredFile file) {
		if (file == null || file.fileId() == null || file.fileId().isBlank()) {
			throw new IllegalArgumentException("file with an id is required");
		}
		if (file.headRevisionId() == null || file.headRevisionId().isBlank()) {
			throw new IllegalArgumentException("file with a revision id is required");
		}
		ExportFormat exportFormat = EXPORT_FORMATS.get(file.mimeType());
		try {
			return backupContent(access, file, exportFormat, false);
		} catch (DriveExportLimitException exception) {
			if (exportFormat == null) {
				throw new IllegalStateException("Unexpected export limit for a non-native Drive file", exception);
			}
			try {
				return backupContent(access, file, new ExportFormat("application/pdf", ".pdf"), true);
			} catch (IOException fallbackException) {
				throw new IllegalStateException("Unable to back up file content using PDF fallback", fallbackException);
			}
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to back up file content", exception);
		}
	}

	private FileVersion backupContent(ServiceAccountAccess access, StoredFile file, ExportFormat exportFormat,
			boolean fallback) throws IOException {
		String fileName = exportFormat == null ? file.name() : withExtension(file.name(), exportFormat.extension());
		try (InputStream content = exportFormat == null
				? contentPort.download(access, file.fileId())
				: contentPort.export(access, file.fileId(), exportFormat.mimeType())) {
			Path localPath = storagePort.store(file.ownerScope(), file.fileId(), file.headRevisionId(), fileName, content);
			FileVersion version = new FileVersion(null, file.fileId(), file.headRevisionId(), Instant.now(),
					localPath.toString(), Files.size(localPath));
			return versionPort.save(version);
		} catch (DriveExportLimitException exception) {
			if (fallback) {
				throw new IllegalStateException("Google PDF fallback also exceeded the export limit", exception);
			}
			throw exception;
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to back up file content", exception);
		}
	}

	static Optional<String> exportMimeTypeFor(String mimeType) {
		return Optional.ofNullable(EXPORT_FORMATS.get(mimeType)).map(ExportFormat::mimeType);
	}

	private static String withExtension(String fileName, String extension) {
		if (fileName == null || fileName.isBlank()) {
			return "unnamed" + extension;
		}
		return fileName.endsWith(extension) ? fileName : fileName + extension;
	}

	private record ExportFormat(String mimeType, String extension) {
	}
}