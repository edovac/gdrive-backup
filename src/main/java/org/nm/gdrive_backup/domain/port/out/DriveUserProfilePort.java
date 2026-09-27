package org.nm.gdrive_backup.domain.port.out;

import org.nm.gdrive_backup.domain.model.DriveUserProfile;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;

public interface DriveUserProfilePort {

	DriveUserProfile getProfile(ServiceAccountAccess access);
}
