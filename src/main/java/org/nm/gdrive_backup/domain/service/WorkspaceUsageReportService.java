package org.nm.gdrive_backup.domain.service;

import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.WorkspaceUsageReport;
import org.nm.gdrive_backup.domain.port.in.WorkspaceUsageReportUseCase;
import org.nm.gdrive_backup.domain.port.out.WorkspaceUsageReportPort;

public class WorkspaceUsageReportService implements WorkspaceUsageReportUseCase {

	private final WorkspaceUsageReportPort reportPort;

	public WorkspaceUsageReportService(WorkspaceUsageReportPort reportPort) {
		this.reportPort = reportPort;
	}

	@Override
	public WorkspaceUsageReport getLatestReport(ServiceAccountAccess access) {
		if (access == null) {
			throw new IllegalArgumentException("access must not be null");
		}
		return reportPort.getLatestReport(access);
	}
}
