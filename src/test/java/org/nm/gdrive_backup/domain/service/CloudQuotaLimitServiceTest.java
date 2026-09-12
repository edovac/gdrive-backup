package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.CloudQuotaLimit;
import org.nm.gdrive_backup.domain.port.out.CloudQuotaLimitPort;

class CloudQuotaLimitServiceTest {

	@Test
	void listQuotaLimitsDelegatesToPort() {
		CloudQuotaLimitPort quotaPort = mock(CloudQuotaLimitPort.class);
		CloudQuotaLimitService service = new CloudQuotaLimitService(quotaPort);
		List<CloudQuotaLimit> limits = List.of(
				new CloudQuotaLimit("drive.googleapis.com", "requests", "Requests", 1000L, 2000L, "1/min"));
		when(quotaPort.listQuotaLimits()).thenReturn(limits);

		assertEquals(limits, service.listQuotaLimits());
	}
}
