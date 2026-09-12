package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.WorkspaceUsageMetric;
import org.nm.gdrive_backup.domain.model.WorkspaceUsageReport;
import org.nm.gdrive_backup.domain.port.out.WorkspaceUsageReportPort;

class WorkspaceUsageReportServiceTest {

	@Test
	void getLatestReportDelegatesToPort() {
		WorkspaceUsageReportPort reportPort = mock(WorkspaceUsageReportPort.class);
		WorkspaceUsageReportService service = new WorkspaceUsageReportService(reportPort);
		ServiceAccountAccess access = access();
		WorkspaceUsageReport report = new WorkspaceUsageReport(
				LocalDate.of(2026, 9, 11), List.of(new WorkspaceUsageMetric("accounts:total_users", "12")));
		when(reportPort.getLatestReport(access)).thenReturn(report);

		assertEquals(report, service.getLatestReport(access));
	}

	@Test
	void getLatestReportRejectsMissingAccess() {
		WorkspaceUsageReportService service = new WorkspaceUsageReportService(mock(WorkspaceUsageReportPort.class));

		assertThrows(IllegalArgumentException.class, () -> service.getLatestReport(null));
	}

	private static ServiceAccountAccess access() {
		return new ServiceAccountAccess(UUID.randomUUID(), "admin@company.com", Instant.now(), Set.of("scope"));
	}
}
