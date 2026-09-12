package org.nm.gdrive_backup.domain.port.in;

import org.nm.gdrive_backup.domain.model.InitialSyncResult;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;

public interface InitialDriveSyncUseCase {

	InitialSyncResult synchronize(ServiceAccountAccess access, String scopeKey);
}