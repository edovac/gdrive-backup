package org.nm.gdrive_backup.domain.port.out;

import org.nm.gdrive_backup.domain.model.DriveUsageQuota;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;

public interface DriveUsageQuotaPort {

	DriveUsageQuota getUsageQuota(ServiceAccountAccess access);
}
