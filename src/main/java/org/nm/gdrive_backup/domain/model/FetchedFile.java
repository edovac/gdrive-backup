package org.nm.gdrive_backup.domain.model;

/**
 * A file's content fetched from Drive and staged, but not yet written to an archive entry.
 *
 * @param content the staged bytes
 * @param exportMimeType the format Drive exported the file in, or {@code null} for files copied as-is
 * @param fallbackFromExtension when the preferred export exceeded Drive's limit and PDF was used instead, the
 *        extension the preferred export would have carried (so the entry name can be rewritten); otherwise {@code null}
 */
public record FetchedFile(StagedContent content, String exportMimeType, String fallbackFromExtension)
		implements AutoCloseable {

	@Override
	public void close() {
		content.close();
	}
}
