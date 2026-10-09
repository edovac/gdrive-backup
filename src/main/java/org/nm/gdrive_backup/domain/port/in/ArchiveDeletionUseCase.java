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

	/**
	 * The same for the chains an earlier from-scratch full left behind: every archive that is neither in the current
	 * chain nor obsolete. Re-reads the whole current chain, since it becomes the drive's only backup. In the plan
	 * {@code mergedArchiveId} is the current chain's root, which takes over the removed archives' history events.
	 */
	Optional<DeletionPlan> prepareEarlierChains(DriveScope scope, String scopeDisplayNameOrNull);

	/** Carries out a plan from {@link #prepareEarlierChains}, with the same refusals as {@link #execute}. */
	DeletionResult executeEarlierChains(DriveScope scope, DeletionPlan plan);
}
