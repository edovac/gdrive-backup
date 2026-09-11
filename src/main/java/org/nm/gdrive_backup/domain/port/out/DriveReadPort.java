package org.nm.gdrive_backup.domain.port.out;

import org.nm.gdrive_backup.domain.model.DriveItem;
import org.nm.gdrive_backup.domain.model.AvailableDrive;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;

import java.util.List;

public interface DriveReadPort {

	List<AvailableDrive> listAvailableDrives(ServiceAccountAccess access);

	List<DriveItem> listMyDriveItems(ServiceAccountAccess access, String parentId);

	List<DriveItem> listSharedDriveItems(ServiceAccountAccess access, String driveId);
}