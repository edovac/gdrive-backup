package org.nm.gdrive_backup.domain.port.out;

import java.util.List;

import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.StoredFile;

public interface DriveFileListingPort {

	List<StoredFile> listAllFiles(ServiceAccountAccess access, DriveScope scope);
}
