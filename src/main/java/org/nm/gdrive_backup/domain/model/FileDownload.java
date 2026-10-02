package org.nm.gdrive_backup.domain.model;

import java.time.Instant;

/**
 * One file's download as shown under the progress bar: in flight until {@code finishedAt} is set, then kept briefly
 * so a download that finishes between two refreshes is still seen.
 *
 * @param name the file's own name, without any folder
 * @param path the file's full path in Drive, such as {@code My Drive/Reports/Q3.pdf}
 * @param finishedAt {@code null} while the download is in flight
 * @param bytesDownloaded how many bytes have arrived so far; the final size once finished
 * @param totalBytes the size Drive reported for the file, or {@code null} when it is not known in advance (a Google
 *        Docs, Sheets or Slides export has none)
 */
public record FileDownload(String fileId, String name, String path, Instant startedAt, Instant finishedAt,
		long bytesDownloaded, Long totalBytes) {

	/** A download with nothing received yet and no known total. */
	public FileDownload(String fileId, String name, String path, Instant startedAt, Instant finishedAt) {
		this(fileId, name, path, startedAt, finishedAt, 0, null);
	}

	public boolean finished() {
		return finishedAt != null;
	}

	public FileDownload withBytesDownloaded(long bytes) {
		return new FileDownload(fileId, name, path, startedAt, finishedAt, bytes, totalBytes);
	}

	public FileDownload withFinishedAt(Instant finished) {
		return new FileDownload(fileId, name, path, startedAt, finished, bytesDownloaded, totalBytes);
	}
}
