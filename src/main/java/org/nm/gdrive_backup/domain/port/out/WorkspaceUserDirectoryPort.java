package org.nm.gdrive_backup.domain.port.out;

import java.util.List;

import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.WorkspaceUser;

public interface WorkspaceUserDirectoryPort {

	List<WorkspaceUser> listUsers(ServiceAccountAccess access);
}
