package org.nm.gdrive_backup.domain.port.in;

import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.SyncResult;

public interface DriveChangeSyncUseCase {

	SyncResult synchronize(ServiceAccountAccess access, DriveScope scope, String scopeDisplayNameOrNull);
}
