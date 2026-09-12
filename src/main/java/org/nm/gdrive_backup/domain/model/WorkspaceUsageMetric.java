package org.nm.gdrive_backup.domain.model;

public record WorkspaceUsageMetric(String name, String value) {

	public WorkspaceUsageMetric {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name must not be blank");
		}
		value = value == null ? "Not reported" : value;
	}
}
