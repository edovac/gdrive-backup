package org.nm.gdrive_backup.domain.port.in;

import org.nm.gdrive_backup.domain.model.DatabaseRebuildResult;
import org.nm.gdrive_backup.domain.model.DatabaseStatus;

public interface DatabaseRebuildUseCase {

	/** Whether the backup database is missing, usable or unusable; a caller confirms with the admin before replacing a usable one. */
	DatabaseStatus status();

	/**
	 * Recreates the database from the manifests of the archives under the backup root, never contacting Google and
	 * never changing an archive. A database that is already there is kept beside the new one under a dated name; a
	 * usable one is only replaced when {@code replaceUsable} is true. Runs alone, reports progress through the backup
	 * progress tracker and honors the shared cancel request, in which case the current database is left as it was.
	 */
	DatabaseRebuildResult rebuild(boolean replaceUsable);
}
