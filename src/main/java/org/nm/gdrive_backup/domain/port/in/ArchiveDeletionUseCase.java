package org.nm.gdrive_backup.domain.port.in;

import java.util.Optional;

import org.nm.gdrive_backup.domain.model.DeletionPlan;
import org.nm.gdrive_backup.domain.model.DeletionResult;
import org.nm.gdrive_backup.domain.model.DriveScope;

public interface ArchiveDeletionUseCase {

	/**
	 * Works out and verifies what deleting the drive's obsolete archives would do, changing nothing. Re-reads the
	 * merged full completely, so it can take a while, reports progress and honors the shared cancel request
	 * (an empty result means it was cancelled).
	 */
	Optional<DeletionPlan> prepare(DriveScope scope, String scopeDisplayNameOrNull);

	/**
	 * Carries out a verified plan: database first, then the files. Refuses a plan that has verification problems or
	 * that no longer matches the drive's chain.
	 */
	DeletionResult execute(DriveScope scope, DeletionPlan plan);
}
