package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.DriveUserProfile;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.port.out.DriveUserProfilePort;

class DriveUserProfileServiceTest {

	@Test
	void getProfileDelegatesToPort() {
		DriveUserProfilePort profilePort = mock(DriveUserProfilePort.class);
		DriveUserProfileService service = new DriveUserProfileService(profilePort);
		ServiceAccountAccess access = access();
		DriveUserProfile profile = new DriveUserProfile("alice@company.com", "Alice", "https://example.com/photo.jpg");
		when(profilePort.getProfile(access)).thenReturn(profile);

		assertEquals(profile, service.getProfile(access));
	}

	@Test
	void getProfileRejectsMissingAccess() {
		DriveUserProfileService service = new DriveUserProfileService(mock(DriveUserProfilePort.class));

		assertThrows(IllegalArgumentException.class, () -> service.getProfile(null));
	}

	private static ServiceAccountAccess access() {
		return new ServiceAccountAccess(UUID.randomUUID(), "alice@company.com", Instant.now(), Set.of("scope"));
	}
}
