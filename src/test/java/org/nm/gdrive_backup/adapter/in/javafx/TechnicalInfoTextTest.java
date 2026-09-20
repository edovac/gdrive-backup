package org.nm.gdrive_backup.adapter.in.javafx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.CloudQuotaLimit;
import org.nm.gdrive_backup.domain.model.DriveUsageQuota;
import org.nm.gdrive_backup.domain.model.WorkspaceUsageMetric;
import org.nm.gdrive_backup.domain.model.WorkspaceUsageReport;

class TechnicalInfoTextTest {

	@Test
	void formatsBytesAndMarksMissingValuesAsNotReported() {
		assertEquals("Not reported", TechnicalInfoText.bytes(null));
		assertEquals("512 B", TechnicalInfoText.bytes(512L));
		assertEquals("2.0 GB", TechnicalInfoText.bytes(2L * 1024 * 1024 * 1024));
	}

	@Test
	void listsTheStorageFigures() {
		DriveUsageQuota quota = new DriveUsageQuota("user@example.com", 1024L, null, 512L, 0L);

		assertEquals(List.of("Used: 1.0 KB", "Limit: Not reported", "Drive: 512 B", "Trash: 0 B"),
				TechnicalInfoText.storageLines(quota));
		assertEquals("Drive storage usage for user@example.com", TechnicalInfoText.storageStatus(quota));
	}

	@Test
	void listsTheReportMetricsAndNamesItsDate() {
		WorkspaceUsageReport report = new WorkspaceUsageReport(LocalDate.of(2026, 9, 18),
				List.of(new WorkspaceUsageMetric("accounts:drive_used_quota_in_mb", "120"),
						new WorkspaceUsageMetric("accounts:total_quota_in_mb", null)));

		assertEquals(List.of("accounts:drive_used_quota_in_mb: 120", "accounts:total_quota_in_mb: Not reported"),
				TechnicalInfoText.reportLines(report));
		assertEquals("Workspace usage report for 2026-09-18 (latest available)", TechnicalInfoText.reportStatus(report));
	}

	@Test
	void describesACloudLimitWithItsDisplayNameOrFallsBackToTheMetric() {
		assertEquals("drive.googleapis.com | Queries per minute | default: 12000 | max: Not reported | unit: 1/min",
				TechnicalInfoText.cloudLine(new CloudQuotaLimit("drive.googleapis.com", "drive/queries",
						"Queries per minute", 12000L, null, "1/min")));
		assertEquals("drive.googleapis.com | drive/queries | default: Not reported | max: 5 | unit: Not reported",
				TechnicalInfoText.cloudLine(new CloudQuotaLimit("drive.googleapis.com", "drive/queries", " ", null,
						5L, null)));
	}

	@Test
	void reportsTheRootCauseOfAFailure() {
		Throwable error = new RuntimeException("wrapper", new IllegalStateException("Reports scope not authorized"));

		assertEquals("Reports scope not authorized", TechnicalInfoText.reason(error));
		assertEquals("IllegalStateException", TechnicalInfoText.reason(new IllegalStateException()));
		assertEquals("Cloud API quota limits unavailable: boom", TechnicalInfoText.cloudFailed("boom"));
	}

	@Test
	void formatsTheUpdateTimeInTheGivenZone() {
		assertEquals("Updated 2026-09-20 10:30:05", TechnicalInfoText.updated(Instant.parse("2026-09-20T08:30:05Z"),
				ZoneId.of("Europe/Rome")));
	}
}
