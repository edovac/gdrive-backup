package org.nm.gdrive_backup.domain.port.out;

import java.io.IOException;
import java.util.List;

import org.nm.gdrive_backup.domain.model.ArchiveEntry;
import org.nm.gdrive_backup.domain.model.ArchiveManifest;

public interface ArchiveWriterPort {

	/** Stages, then atomically publishes, a ZIP at backupRoot/{@code relativeTargetPath} with manifest.json embedded. */
	void write(String relativeTargetPath, List<ArchiveEntry> entries, ArchiveManifest manifest) throws IOException;
}
