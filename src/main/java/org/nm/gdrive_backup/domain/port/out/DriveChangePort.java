package org.nm.gdrive_backup.domain.port.out;

import org.nm.gdrive_backup.domain.model.DriveChangePage;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.PersonalDriveContent;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;

public interface DriveChangePort {

	String getStartPageToken(ServiceAccountAccess access, DriveScope scope);

	/**
	 * {@code content} only narrows a personal scope: a change to a file outside it is reported as
	 * {@link org.nm.gdrive_backup.domain.model.DriveChange#outOfScope}.
	 */
	DriveChangePage listChanges(ServiceAccountAccess access, DriveScope scope, String pageToken,
			PersonalDriveContent content);
}
