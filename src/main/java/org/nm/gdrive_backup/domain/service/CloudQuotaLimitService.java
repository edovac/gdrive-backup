package org.nm.gdrive_backup.domain.service;

import java.util.List;
import org.nm.gdrive_backup.domain.model.CloudQuotaLimit;
import org.nm.gdrive_backup.domain.port.in.CloudQuotaLimitUseCase;
import org.nm.gdrive_backup.domain.port.out.CloudQuotaLimitPort;

public class CloudQuotaLimitService implements CloudQuotaLimitUseCase {

	private final CloudQuotaLimitPort quotaLimitPort;

	public CloudQuotaLimitService(CloudQuotaLimitPort quotaLimitPort) {
		this.quotaLimitPort = quotaLimitPort;
	}

	@Override
	public List<CloudQuotaLimit> listQuotaLimits() {
		return quotaLimitPort.listQuotaLimits();
	}
}
