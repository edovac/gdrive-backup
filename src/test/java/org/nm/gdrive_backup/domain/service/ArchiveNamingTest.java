package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.DriveScope;

class ArchiveNamingTest {

	@Test
	void personalScopeFolderNameUsesTheEmail() {
		assertEquals("My Drive (edoardo@example.com)",
				ArchiveNaming.scopeFolderName(DriveScope.personal("edoardo@example.com"), null));
	}

	@Test
	void sharedDriveScopeFolderNameUsesTheDisplayNameAndId() {
		assertEquals("Finance (drive-1)",
				ArchiveNaming.scopeFolderName(DriveScope.sharedDrive("drive-1"), "Finance"));
	}

	@Test
	void sharedDriveScopeFolderNameFallsBackWhenNoDisplayName() {
		assertEquals("Shared Drive (drive-1)",
				ArchiveNaming.scopeFolderName(DriveScope.sharedDrive("drive-1"), null));
	}

	@Test
	void scopeFolderNameIsSanitized() {
		assertEquals("Q1_ Finance (drive-1)",
				ArchiveNaming.scopeFolderName(DriveScope.sharedDrive("drive-1"), "Q1: Finance"));
	}

	@Test
	void archiveFileNameZeroPadsTheSequenceNumberToFourDigits() {
		assertEquals("archive-0001-full.zip", ArchiveNaming.archiveFileName(1, ArchiveMode.FULL));
		assertEquals("archive-0042-incremental.zip", ArchiveNaming.archiveFileName(42, ArchiveMode.INCREMENTAL));
		assertEquals("archive-9999-full.zip", ArchiveNaming.archiveFileName(9999, ArchiveMode.FULL));
	}

	@Test
	void archiveFileNameUsesKebabCaseForEveryMode() {
		assertEquals("archive-0001-full.zip", ArchiveNaming.archiveFileName(1, ArchiveMode.FULL));
		assertEquals("archive-0001-incremental.zip", ArchiveNaming.archiveFileName(1, ArchiveMode.INCREMENTAL));
		assertEquals("archive-0001-merged-incremental.zip",
				ArchiveNaming.archiveFileName(1, ArchiveMode.MERGED_INCREMENTAL));
		assertEquals("archive-0001-merged-full.zip", ArchiveNaming.archiveFileName(1, ArchiveMode.MERGED_FULL));
	}

	@Test
	void sanitizeSegmentReplacesOnlyCharactersIllegalOnWindows() {
		assertEquals("a_b_c_d_e_f_g_h_i_", ArchiveNaming.sanitizeSegment("a\\b/c:d*e?f\"g<h>i|"));
	}

	@Test
	void sanitizeSegmentPreservesAccentedAndUnicodeNames() {
		assertEquals("Café Résumé — 日本語", ArchiveNaming.sanitizeSegment("Café Résumé — 日本語"));
	}
}
