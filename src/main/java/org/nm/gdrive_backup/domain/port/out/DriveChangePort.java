package org.nm.gdrive_backup.domain.port.out;

import org.nm.gdrive_backup.domain.model.DriveChangePage;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;

public interface DriveChangePort {

	String getStartPageToken(ServiceAccountAccess access, DriveScope scope);

	DriveChangePage listChanges(ServiceAccountAccess access, DriveScope scope, String pageToken);
}
