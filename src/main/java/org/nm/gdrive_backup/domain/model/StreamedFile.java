package org.nm.gdrive_backup.domain.model;

/** An uncommitted capture plus the format Drive exported it in ({@code null} for files copied as-is). */
public record StreamedFile(FileCapture capture, String exportMimeType) {
}
