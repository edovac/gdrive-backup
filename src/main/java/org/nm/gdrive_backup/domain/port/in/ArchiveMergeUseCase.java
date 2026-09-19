package org.nm.gdrive_backup.domain.port.in;

import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.DriveScope;

public interface ArchiveMergeUseCase {

	/**
	 * Builds a full archive ({@code MERGED_FULL}) from the drive's current chain: its root plus every incremental,
	 * reading only archives. The result becomes the chain's new root; nothing is deleted.
	 */
	Archive merge(DriveScope scope, String scopeDisplayNameOrNull);
}
