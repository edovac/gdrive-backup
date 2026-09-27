package org.nm.gdrive_backup.domain.service;

import org.nm.gdrive_backup.domain.model.DriveUserProfile;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.port.in.DriveUserProfileUseCase;
import org.nm.gdrive_backup.domain.port.out.DriveUserProfilePort;

public class DriveUserProfileService implements DriveUserProfileUseCase {

	private final DriveUserProfilePort userProfilePort;

	public DriveUserProfileService(DriveUserProfilePort userProfilePort) {
		this.userProfilePort = userProfilePort;
	}

	@Override
	public DriveUserProfile getProfile(ServiceAccountAccess access) {
		if (access == null) {
			throw new IllegalArgumentException("access must not be null");
		}
		return userProfilePort.getProfile(access);
	}
}
