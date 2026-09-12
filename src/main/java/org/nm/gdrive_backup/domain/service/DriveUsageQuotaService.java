package org.nm.gdrive_backup.domain.service;

import org.nm.gdrive_backup.domain.model.DriveUsageQuota;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.port.in.DriveUsageQuotaUseCase;
import org.nm.gdrive_backup.domain.port.out.DriveUsageQuotaPort;

public class DriveUsageQuotaService implements DriveUsageQuotaUseCase {

	private final DriveUsageQuotaPort usageQuotaPort;

	public DriveUsageQuotaService(DriveUsageQuotaPort usageQuotaPort) {
		this.usageQuotaPort = usageQuotaPort;
	}

	@Override
	public DriveUsageQuota getUsageQuota(ServiceAccountAccess access) {
		if (access == null) {
			throw new IllegalArgumentException("access must not be null");
		}
		return usageQuotaPort.getUsageQuota(access);
	}
}
