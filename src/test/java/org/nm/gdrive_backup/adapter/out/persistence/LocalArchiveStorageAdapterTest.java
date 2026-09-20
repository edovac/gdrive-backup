package org.nm.gdrive_backup.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalArchiveStorageAdapterTest {

	private static final String ARCHIVE = "archives/scope/archive-0001-full.zip";

	@TempDir
	Path temporaryDirectory;

	private LocalBackupRoot root;
	private LocalArchiveStorageAdapter adapter;

	@BeforeEach
	void setUp() throws Exception {
		root = new LocalBackupRoot(temporaryDirectory);
		adapter = new LocalArchiveStorageAdapter(root);
		Files.createDirectories(temporaryDirectory.resolve("archives/scope"));
		Files.write(temporaryDirectory.resolve(ARCHIVE), new byte[] { 1, 2, 3 });
	}

	@Test
	void reportsTheSizeOfAnExistingArchiveAndNothingForAMissingOne() {
		assertEquals(OptionalLong.of(3), adapter.sizeOf(ARCHIVE));
		assertEquals(OptionalLong.empty(), adapter.sizeOf("archives/scope/nope.zip"));
	}

	@Test
	void deletesAnArchiveAndIgnoresOneThatIsAlreadyGone() throws Exception {
		adapter.delete(ARCHIVE);
		adapter.delete(ARCHIVE);

		assertFalse(Files.exists(temporaryDirectory.resolve(ARCHIVE)));
	}

	@Test
	void refusesPathsOutsideTheArchivesDirectory() throws Exception {
		Files.writeString(temporaryDirectory.resolve("backup.db"), "database");

		assertThrows(IllegalArgumentException.class, () -> adapter.delete("backup.db"));
		assertThrows(IllegalArgumentException.class, () -> adapter.delete("archives/../backup.db"));
		assertThrows(IllegalArgumentException.class, () -> adapter.delete("../outside.zip"));
		assertThrows(IllegalArgumentException.class, () -> adapter.delete("archives"));
		assertTrue(Files.exists(temporaryDirectory.resolve("backup.db")));
		assertEquals(OptionalLong.empty(), adapter.sizeOf("backup.db"));
	}

	@Test
	void followsTheBackupRootAcrossASwitch() throws Exception {
		Path second = temporaryDirectory.resolve("second");
		root.switchTo(second);
		Files.createDirectories(second.resolve("archives/scope"));
		Files.write(second.resolve(ARCHIVE), new byte[] { 9 });

		assertEquals(OptionalLong.of(1), adapter.sizeOf(ARCHIVE));
	}
}
