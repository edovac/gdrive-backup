package org.nm.gdrive_backup.domain.model;

/**
 * Content already copied out of Drive and held by an {@link ArchiveSession} until the writer appends it to the
 * archive. Its size and CRC-32 are known up front, which lets the session store it without compressing it.
 * Closing releases whatever backs it; a session also releases anything still outstanding when it is discarded.
 */
public interface StagedContent extends AutoCloseable {

	long size();

	long crc32();

	@Override
	void close();
}
