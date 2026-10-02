package org.nm.gdrive_backup.domain.model;

import java.time.Instant;

/**
 * One file's download as shown under the progress bar: in flight until {@code finishedAt} is set, then kept briefly
 * so a download that finishes between two refreshes is still seen.
 *
 * @param name the file's own name, without any folder
 * @param path the file's full path in Drive, such as {@code My Drive/Reports/Q3.pdf}
 * @param finishedAt {@code null} while the download is in flight
 */
public record FileDownload(String fileId, String name, String path, Instant startedAt, Instant finishedAt) {

	public boolean finished() {
		return finishedAt != null;
	}
}
