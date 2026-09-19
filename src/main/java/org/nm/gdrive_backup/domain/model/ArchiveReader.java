package org.nm.gdrive_backup.domain.model;

import java.io.IOException;
import java.io.InputStream;

/** An archive opened for reading: its manifest, and its entries as streams. */
public interface ArchiveReader extends AutoCloseable {

	ArchiveManifest manifest();

	/** Streams one entry; the caller closes the stream. Throws if the archive has no such entry. */
	InputStream openEntry(String entryName) throws IOException;

	@Override
	void close();
}
