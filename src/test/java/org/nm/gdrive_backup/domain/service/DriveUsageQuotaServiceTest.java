package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.DriveUsageQuota;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.port.out.DriveUsageQuotaPort;

class DriveUsageQuotaServiceTest {

	@Test
	void getUsageQuotaDelegatesToPort() {
		DriveUsageQuotaPort quotaPort = mock(DriveUsageQuotaPort.class);
		DriveUsageQuotaService service = new DriveUsageQuotaService(quotaPort);
		ServiceAccountAccess access = access();
		DriveUsageQuota quota = new DriveUsageQuota("alice@company.com", 100L, 1000L, 90L, 10L);
		when(quotaPort.getUsageQuota(access)).thenReturn(quota);

		assertEquals(quota, service.getUsageQuota(access));
	}

	@Test
	void getUsageQuotaRejectsMissingAccess() {
		DriveUsageQuotaService service = new DriveUsageQuotaService(mock(DriveUsageQuotaPort.class));

		assertThrows(IllegalArgumentException.class, () -> service.getUsageQuota(null));
	}

	private static ServiceAccountAccess access() {
		return new ServiceAccountAccess(UUID.randomUUID(), "alice@company.com", Instant.now(), Set.of("scope"));
	}
}
