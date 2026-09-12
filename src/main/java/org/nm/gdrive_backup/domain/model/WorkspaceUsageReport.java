package org.nm.gdrive_backup.domain.model;

import java.time.LocalDate;
import java.util.List;

public record WorkspaceUsageReport(LocalDate date, List<WorkspaceUsageMetric> metrics) {

	public WorkspaceUsageReport {
		if (date == null) {
			throw new IllegalArgumentException("date must not be null");
		}
		metrics = metrics == null ? List.of() : List.copyOf(metrics);
	}
}
