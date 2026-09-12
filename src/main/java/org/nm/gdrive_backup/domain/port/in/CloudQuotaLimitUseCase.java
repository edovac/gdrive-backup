package org.nm.gdrive_backup.domain.port.in;

import java.util.List;
import org.nm.gdrive_backup.domain.model.CloudQuotaLimit;

public interface CloudQuotaLimitUseCase {

	List<CloudQuotaLimit> listQuotaLimits();
}
