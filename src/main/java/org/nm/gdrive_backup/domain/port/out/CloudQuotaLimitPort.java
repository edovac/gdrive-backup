package org.nm.gdrive_backup.domain.port.out;

import java.util.List;
import org.nm.gdrive_backup.domain.model.CloudQuotaLimit;

public interface CloudQuotaLimitPort {

	List<CloudQuotaLimit> listQuotaLimits();
}
