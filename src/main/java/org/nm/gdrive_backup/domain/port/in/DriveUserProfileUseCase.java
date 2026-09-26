package org.nm.gdrive_backup.domain.port.in;

import org.nm.gdrive_backup.domain.model.DriveUserProfile;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;

public interface DriveUserProfileUseCase {

	DriveUserProfile getProfile(ServiceAccountAccess access);
}
