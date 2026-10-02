package org.nm.gdrive_backup.domain.port.in;

import org.nm.gdrive_backup.domain.model.PersonalDriveContent;

/** Which files a personal drive backup takes, which the admin can change for the session. */
public interface PersonalDriveContentUseCase {

	PersonalDriveContent current();

	/** Applies to the next backup run. Throws {@link IllegalStateException} while a backup is running. */
	void change(PersonalDriveContent value);
}
