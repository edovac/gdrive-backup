package org.nm.gdrive_backup.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalBackupRootSweepTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void removesTheTempFilesOfAnInterruptedRunAndNothingElse() throws Exception {
		LocalBackupRoot root = new LocalBackupRoot(temporaryDirectory);
		Path drive = Files.createDirectories(temporaryDirectory.resolve("archives/My Drive (user@example.com)"));
		Path partial = Files.createFile(drive.resolve(".archive-123.zip.tmp"));
		Path spool = Files.createFile(drive.resolve(".spool-456.tmp"));
		Path archive = Files.createFile(drive.resolve("archive-0001-full.zip"));
		Path database = Files.createFile(temporaryDirectory.resolve("backup.db"));
		Path outsideArchives = Files.createFile(temporaryDirectory.resolve(".spool-789.tmp"));

		assertEquals(2, root.sweepLeftovers());

		assertFalse(Files.exists(partial));
		assertFalse(Files.exists(spool));
		assertTrue(Files.exists(archive));
		assertTrue(Files.exists(database));
		assertTrue(Files.exists(outsideArchives), "only the archives folder is swept");
	}

	@Test
	void doesNothingWhenThereAreNoArchivesYet() {
		assertEquals(0, new LocalBackupRoot(temporaryDirectory).sweepLeftovers());
	}
}
