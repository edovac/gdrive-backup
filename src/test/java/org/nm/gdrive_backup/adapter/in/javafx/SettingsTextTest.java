package org.nm.gdrive_backup.adapter.in.javafx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.PersonalDriveContent;

class SettingsTextTest {

	@Test
	void theConcurrencyHintStatesTheRangeAndThatItAppliesToTheNextBackupOnly() {
		String hint = SettingsText.downloadConcurrencyHint(1, 16);

		assertTrue(hint.contains("1 to 16"));
		assertTrue(hint.contains("next backup"));
		assertTrue(hint.contains("restarts"));
	}

	@Test
	void theSavedMessageUsesTheSingularForOneFile() {
		assertEquals("Downloading 1 file at once from the next backup.", SettingsText.downloadConcurrencySaved(1));
		assertEquals("Downloading 8 files at once from the next backup.", SettingsText.downloadConcurrencySaved(8));
	}

	@Test
	void theFailureMessageCarriesTheReason() {
		assertEquals("Unable to change the number of parallel downloads: Backup locations can't change while a backup "
				+ "is running", SettingsText.downloadConcurrencySaveFailed(
						"Backup locations can't change while a backup is running"));
	}

	@Test
	void thePersonalDriveHintSaysAFullBackupAppliesItToTheWholeDrive() {
		String hint = SettingsText.includeSharedFilesHint();

		assertTrue(hint.contains("only the files the user owns"));
		assertTrue(hint.contains("Full backup"));
		assertTrue(hint.contains("restarts"));
	}

	@Test
	void thePersonalDriveSavedMessageNamesTheChoice() {
		assertEquals("Personal drive backups include files shared with the user from the next backup.",
				SettingsText.personalDriveContentSaved(PersonalDriveContent.ALL_ACCESSIBLE));
		assertEquals("Personal drive backups take only the user's own files from the next backup.",
				SettingsText.personalDriveContentSaved(PersonalDriveContent.OWNED_ONLY));
	}

	@Test
	void theReasonIsTheRootCausesMessage() {
		Throwable error = new RuntimeException("outer", new IllegalStateException("inner reason"));

		assertEquals("inner reason", SettingsText.reason(error));
	}
}
