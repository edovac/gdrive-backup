package org.nm.gdrive_backup.domain.model;

public record DriveUsageQuota(
		String userEmail,
		Long usageBytes,
		Long limitBytes,
		Long driveUsageBytes,
		Long trashUsageBytes) {
}
