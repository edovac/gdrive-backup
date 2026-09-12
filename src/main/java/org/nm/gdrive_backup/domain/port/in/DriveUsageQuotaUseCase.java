package org.nm.gdrive_backup.domain.port.in;

import org.nm.gdrive_backup.domain.model.DriveUsageQuota;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;

public interface DriveUsageQuotaUseCase {

	DriveUsageQuota getUsageQuota(ServiceAccountAccess access);
}
