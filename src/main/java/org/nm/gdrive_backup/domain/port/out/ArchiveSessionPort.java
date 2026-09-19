package org.nm.gdrive_backup.domain.port.out;

import java.io.IOException;

import org.nm.gdrive_backup.domain.model.ArchiveSession;

public interface ArchiveSessionPort {

	/** Starts staging an archive that {@link ArchiveSession#publish} will place at backupRoot/{@code relativeTargetPath}. */
	ArchiveSession open(String relativeTargetPath) throws IOException;
}
