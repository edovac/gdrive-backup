package org.nm.gdrive_backup.domain.port.out;

import org.nm.gdrive_backup.domain.model.DriveChangePage;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;

public interface DriveChangePort {

	String getStartPageToken(ServiceAccountAccess access, String scopeKey);

	DriveChangePage listChanges(ServiceAccountAccess access, String scopeKey, String pageToken);
}