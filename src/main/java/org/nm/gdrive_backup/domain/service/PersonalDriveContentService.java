package org.nm.gdrive_backup.domain.service;

import java.util.Objects;

import org.nm.gdrive_backup.domain.model.PersonalDriveContent;
import org.nm.gdrive_backup.domain.port.in.PersonalDriveContentUseCase;

/**
 * Holds the session's choice of personal drive content. The sync services read {@link #current} when a run starts,
 * and a change is refused while a backup runs, so one run never changes what it includes half way through.
 */
public class PersonalDriveContentService implements PersonalDriveContentUseCase {

	private final BackupActivity backupActivity;
	private volatile PersonalDriveContent current;

	public PersonalDriveContentService(PersonalDriveContent initial, BackupActivity backupActivity) {
		this.current = Objects.requireNonNull(initial, "initial must not be null");
		this.backupActivity = backupActivity;
	}

	@Override
	public PersonalDriveContent current() {
		return current;
	}

	@Override
	public void change(PersonalDriveContent value) {
		PersonalDriveContent target = Objects.requireNonNull(value, "value must not be null");
		backupActivity.changeLocations(() -> current = target);
	}
}
