package org.nm.gdrive_backup.domain.model;

public record CloudQuotaLimit(
		String service,
		String metric,
		String displayName,
		Long defaultLimit,
		Long maxLimit,
		String unit) {
}
