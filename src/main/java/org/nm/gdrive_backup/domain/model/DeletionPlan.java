package org.nm.gdrive_backup.domain.model;

import java.util.List;

/**
 * What deleting a drive's obsolete archives would do, worked out and verified without changing anything.
 * {@code verificationProblems} must be empty before the plan can be executed. {@code tipArchiveId} and the
 * obsolete ids identify the chain the plan was made for, so a plan that no longer matches is refused.
 */
public record DeletionPlan(
		DriveScope scope,
		long mergedArchiveId,
		long tipArchiveId,
		List<ObsoleteArchive> obsolete,
		List<LostContent> lostContent,
		int capturesToRepoint,
		int capturesToRemove,
		int eventsToRepoint,
		List<String> verificationProblems) {

	public boolean verified() {
		return verificationProblems.isEmpty();
	}

	public long totalBytes() {
		return obsolete.stream().mapToLong(archive -> archive.sizeBytes() == null ? 0 : archive.sizeBytes()).sum();
	}
}
