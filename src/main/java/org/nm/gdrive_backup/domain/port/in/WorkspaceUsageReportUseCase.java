package org.nm.gdrive_backup.domain.port.in;

import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.WorkspaceUsageReport;

public interface WorkspaceUsageReportUseCase {

	WorkspaceUsageReport getLatestReport(ServiceAccountAccess access);
}
