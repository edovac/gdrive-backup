package org.nm.gdrive_backup.domain.port.in;

import java.util.Optional;

import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.SyncResult;

public interface ArchivePackagingUseCase {

	/** A full run always produces an archive, even an empty one — that proves the run happened. */
	Archive packageFullArchive(DriveScope scope, String scopeDisplayNameOrNull);

	/** Empty when the run's changes feed produced neither an event nor a captured file. */
	Optional<Archive> packageIncrementalArchive(DriveScope scope, String scopeDisplayNameOrNull, SyncResult result);
}
