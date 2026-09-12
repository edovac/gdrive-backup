package org.nm.gdrive_backup.domain.service;

import java.util.List;

import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.WorkspaceUser;
import org.nm.gdrive_backup.domain.port.in.WorkspaceUserListingUseCase;
import org.nm.gdrive_backup.domain.port.out.WorkspaceUserDirectoryPort;

public class WorkspaceUserListingService implements WorkspaceUserListingUseCase {

	private final WorkspaceUserDirectoryPort directoryPort;

	public WorkspaceUserListingService(WorkspaceUserDirectoryPort directoryPort) {
		this.directoryPort = directoryPort;
	}

	@Override
	public List<WorkspaceUser> listUsers(ServiceAccountAccess access) {
		if (access == null) {
			throw new IllegalArgumentException("access must not be null");
		}
		return directoryPort.listUsers(access);
	}
}
