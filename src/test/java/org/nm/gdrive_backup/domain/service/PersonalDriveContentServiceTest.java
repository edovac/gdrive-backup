package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.PersonalDriveContent;

class PersonalDriveContentServiceTest {

	private final BackupActivity activity = new BackupActivity();
	private final PersonalDriveContentService service =
			new PersonalDriveContentService(PersonalDriveContent.OWNED_ONLY, activity);

	@Test
	void startsAtTheConfiguredValue() {
		assertEquals(PersonalDriveContent.OWNED_ONLY, service.current());
	}

	@Test
	void changesTheValue() {
		service.change(PersonalDriveContent.ALL_ACCESSIBLE);

		assertEquals(PersonalDriveContent.ALL_ACCESSIBLE, service.current());
	}

	@Test
	void rejectsAMissingValue() {
		assertThrows(NullPointerException.class, () -> service.change(null));
		assertThrows(NullPointerException.class, () -> new PersonalDriveContentService(null, activity));
	}

	@Test
	void refusesToChangeWhileABackupIsRunning() {
		assertThrows(IllegalStateException.class, () -> activity.duringBackup(() -> {
			service.change(PersonalDriveContent.ALL_ACCESSIBLE);
			return null;
		}));

		assertEquals(PersonalDriveContent.OWNED_ONLY, service.current());
	}
}
