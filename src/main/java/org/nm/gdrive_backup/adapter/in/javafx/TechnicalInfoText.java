package org.nm.gdrive_backup.adapter.in.javafx;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.nm.gdrive_backup.domain.model.CloudQuotaLimit;
import org.nm.gdrive_backup.domain.model.DriveUsageQuota;
import org.nm.gdrive_backup.domain.model.WorkspaceUsageReport;

/** Wording and formatting for the Technical info view, kept free of JavaFX so it can be unit tested. */
final class TechnicalInfoText {

	private static final String NOT_REPORTED = "Not reported";
	private static final DateTimeFormatter UPDATED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private TechnicalInfoText() {
	}

	static String notLoaded() {
		return "Press Refresh to load";
	}

	static String updated(Instant at, ZoneId zone) {
		return "Updated " + UPDATED.format(at.atZone(zone));
	}

	static String storageLoading(String userEmail) {
		return "Loading Drive storage usage for " + userEmail + "...";
	}

	static String storageStatus(DriveUsageQuota quota) {
		return "Drive storage usage for " + quota.userEmail();
	}

	static String storageUnavailable() {
		return "Drive storage usage unavailable. Configure service-account access and select a user.";
	}

	static String storageFailed(String reason) {
		return "Drive storage usage unavailable: " + reason;
	}

	static List<String> storageLines(DriveUsageQuota quota) {
		return List.of(
				"Used: " + bytes(quota.usageBytes()),
				"Limit: " + bytes(quota.limitBytes()),
				"Drive: " + bytes(quota.driveUsageBytes()),
				"Trash: " + bytes(quota.trashUsageBytes()));
	}

	static String reportLoading() {
		return "Loading Workspace usage report...";
	}

	static String reportStatus(WorkspaceUsageReport report) {
		return reportStatus(report.date());
	}

	static String reportStatus(LocalDate date) {
		return "Workspace usage report for " + date + " (latest available)";
	}

	static String reportUnavailable() {
		return "Workspace usage report unavailable. Authorize the Reports scope and select a user.";
	}

	static String reportFailed(String reason) {
		return "Workspace usage report unavailable: " + reason;
	}

	static List<String> reportLines(WorkspaceUsageReport report) {
		return report.metrics().stream().map(metric -> metric.name() + ": " + metric.value()).toList();
	}

	static String cloudLoading() {
		return "Loading Cloud API quota limits...";
	}

	static String cloudStatus() {
		return "Cloud API quota limits from the configured project";
	}

	static String cloudUnavailable() {
		return "Cloud API quota limits unavailable. Configure project quota access.";
	}

	static String cloudFailed(String reason) {
		return "Cloud API quota limits unavailable: " + reason;
	}

	static String cloudLine(CloudQuotaLimit limit) {
		String name = limit.displayName() == null || limit.displayName().isBlank()
				? limit.metric() : limit.displayName();
		return limit.service() + " | " + name + " | default: " + valueOrNotReported(limit.defaultLimit())
				+ " | max: " + valueOrNotReported(limit.maxLimit()) + " | unit: " + valueOrNotReported(limit.unit());
	}

	static String bytes(Long bytes) {
		return bytes == null ? NOT_REPORTED : ArchiveManagerText.size(bytes);
	}

	/** The root cause's message, which is what the admin can act on. */
	static String reason(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
	}

	private static String valueOrNotReported(Object value) {
		return value == null ? NOT_REPORTED : value.toString();
	}
}
