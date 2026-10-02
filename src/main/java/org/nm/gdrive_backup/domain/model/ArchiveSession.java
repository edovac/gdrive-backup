package org.nm.gdrive_backup.domain.model;

import java.io.IOException;
import java.io.InputStream;

/**
 * One archive being staged. Content streams in entry by entry; {@link #publish} appends the manifest and
 * makes the archive visible at its final path. Closing a session that was not published discards it.
 */
public interface ArchiveSession extends AutoCloseable {

	/** Streams {@code content} into a new entry and returns the number of bytes written. */
	long writeEntry(String entryName, InputStream content) throws IOException;

	/**
	 * Copies {@code content} into session-owned staging and returns it. Safe to call from several threads at once,
	 * which is what lets downloads overlap while {@link #writeEntry(String, StagedContent, boolean)} stays single-threaded.
	 */
	StagedContent stage(InputStream content) throws IOException;

	/**
	 * Appends staged content as a new entry and releases it; returns the number of bytes written. When
	 * {@code compress} is false the bytes are stored as-is, which suits content that is already compressed.
	 */
	long writeEntry(String entryName, StagedContent staged, boolean compress) throws IOException;

	boolean containsEntry(String entryName);

	void publish(ArchiveManifest manifest) throws IOException;

	void discard();

	@Override
	void close();
}
