package org.nm.gdrive_backup.domain.port.out;

import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.WorkspaceUsageReport;

public interface WorkspaceUsageReportPort {

	WorkspaceUsageReport getLatestReport(ServiceAccountAccess access);
}
