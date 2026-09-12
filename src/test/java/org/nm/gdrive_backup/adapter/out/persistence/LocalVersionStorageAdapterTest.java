package org.nm.gdrive_backup.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalVersionStorageAdapterTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void storesVersionUnderOwnerFileAndRevisionDirectories() throws Exception {
		LocalVersionStorageAdapter adapter = new LocalVersionStorageAdapter(temporaryDirectory);

		Path stored = adapter.store("user@example.com", "file-1", "revision-1", "Report.pdf",
				new ByteArrayInputStream("backup-content".getBytes(StandardCharsets.UTF_8)));

		assertEquals(temporaryDirectory.resolve("user@example.com/file-1/revision-1/Report.pdf"), stored);
		assertEquals("backup-content", Files.readString(stored));
	}

	@Test
	void sanitizesPathSeparatorsInInputValues() throws Exception {
		LocalVersionStorageAdapter adapter = new LocalVersionStorageAdapter(temporaryDirectory);

		Path stored = adapter.store("../user", "file/1", "revision:1", "../Report.pdf",
				new ByteArrayInputStream(new byte[] { 1 }));

		assertEquals(temporaryDirectory, stored.getParent().getParent().getParent().getParent());
		assertEquals(".._user", stored.getParent().getParent().getParent().getFileName().toString());
	}

	@Test
	void rejectsMissingContent() {
		LocalVersionStorageAdapter adapter = new LocalVersionStorageAdapter(temporaryDirectory);

		assertThrows(IllegalArgumentException.class,
				() -> adapter.store("user", "file", "revision", "file.txt", null));
	}
}