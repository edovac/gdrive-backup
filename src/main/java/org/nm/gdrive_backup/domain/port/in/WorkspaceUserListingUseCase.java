package org.nm.gdrive_backup.domain.port.in;

import java.util.List;

import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.WorkspaceUser;

public interface WorkspaceUserListingUseCase {

	List<WorkspaceUser> listUsers(ServiceAccountAccess access);
}
