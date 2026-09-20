package org.nm.gdrive_backup.domain.port.in;

import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.MergeResult;

public interface ArchiveMergeUseCase {

	/**
	 * Builds a full archive ({@code MERGED_FULL}) from the drive's current chain: its root plus every incremental,
	 * reading only archives. The result becomes the chain's new root; nothing is deleted. Runs alone, reports progress
	 * through the backup progress tracker and honors the shared cancel request.
	 */
	MergeResult merge(DriveScope scope, String scopeDisplayNameOrNull);
}
