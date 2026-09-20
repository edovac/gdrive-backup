package org.nm.gdrive_backup.domain.port.out;

import java.io.IOException;
import java.util.OptionalLong;

public interface ArchiveStoragePort {

	/** The size of the archive file at backupRoot/{@code relativePath}, or empty when it is missing. */
	OptionalLong sizeOf(String relativePath);

	/** Deletes the archive file; a file that is already gone is not an error. Only paths under archives/ are allowed. */
	void delete(String relativePath) throws IOException;
}
