package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.WorkspaceUser;
import org.nm.gdrive_backup.domain.port.out.WorkspaceUserDirectoryPort;

class WorkspaceUserListingServiceTest {

	@Test
	void listUsersDelegatesToPort() {
		WorkspaceUserDirectoryPort directoryPort = mock(WorkspaceUserDirectoryPort.class);
		WorkspaceUserListingService service = new WorkspaceUserListingService(directoryPort);
		ServiceAccountAccess access = new ServiceAccountAccess(
				UUID.randomUUID(),
				"admin@company.com",
				Instant.now(),
				Set.of("scope"));
		WorkspaceUser user = new WorkspaceUser("alice@company.com", "Alice Example");
		when(directoryPort.listUsers(access)).thenReturn(List.of(user));

		assertEquals(List.of(user), service.listUsers(access));
	}
}
