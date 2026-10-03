package org.nm.gdrive_backup.domain.port.out;

import java.io.IOException;
import java.util.List;

public interface ArchiveScanPort {

	/** Paths, relative to the backup root, of every archive ZIP under archives/, in a stable order. */
	List<String> listArchivePaths() throws IOException;
}
