package org.nm.gdrive_backup.domain.port.out;

import java.io.IOException;
import java.nio.file.NoSuchFileException;

import org.nm.gdrive_backup.domain.model.ArchiveReader;

public interface ArchiveReaderPort {

	/**
	 * Opens the archive at backupRoot/{@code relativePath}. Throws {@link NoSuchFileException} when the file is
	 * missing and {@link IOException} when it is not a readable archive with a supported manifest.
	 */
	ArchiveReader open(String relativePath) throws IOException;
}
