package org.nm.gdrive_backup.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nm.gdrive_backup.domain.model.StoredCapture;

class LocalCaptureStorageAdapterTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void storesCaptureUnderOwnerAndFileDirectories() throws Exception {
		LocalCaptureStorageAdapter adapter = new LocalCaptureStorageAdapter(temporaryDirectory);

		StoredCapture stored = adapter.store("user@example.com", "file-1", "Report.pdf",
				new ByteArrayInputStream("backup-content".getBytes(StandardCharsets.UTF_8)));

		assertEquals(Path.of("user@example.com/file-1/Report.pdf"), stored.relativePath());
		assertEquals(14, stored.sizeBytes());
		assertEquals("backup-content", Files.readString(temporaryDirectory.resolve(stored.relativePath())));
	}

	@Test
	void replacesThePreviousCaptureWhenContentChanges() throws Exception {
		LocalCaptureStorageAdapter adapter = new LocalCaptureStorageAdapter(temporaryDirectory);
		adapter.store("user@example.com", "file-1", "Report.pdf",
				new ByteArrayInputStream("first-content".getBytes(StandardCharsets.UTF_8)));

		StoredCapture stored = adapter.store("user@example.com", "file-1", "Report.pdf",
				new ByteArrayInputStream("second-content".getBytes(StandardCharsets.UTF_8)));

		assertEquals("second-content", Files.readString(temporaryDirectory.resolve(stored.relativePath())));
	}

	@Test
	void sanitizesPathSeparatorsInInputValues() throws Exception {
		LocalCaptureStorageAdapter adapter = new LocalCaptureStorageAdapter(temporaryDirectory);

		StoredCapture stored = adapter.store("../user", "file/1", "../Report.pdf",
				new ByteArrayInputStream(new byte[] { 1 }));

		Path storedPath = temporaryDirectory.resolve(stored.relativePath());
		assertEquals(temporaryDirectory, storedPath.getParent().getParent().getParent());
		assertEquals(".._user", storedPath.getParent().getParent().getFileName().toString());
	}

	@Test
	void rejectsMissingContent() {
		LocalCaptureStorageAdapter adapter = new LocalCaptureStorageAdapter(temporaryDirectory);

		assertThrows(IllegalArgumentException.class,
				() -> adapter.store("user", "file", "file.txt", null));
	}

	@Test
	void switchStoresLaterCapturesUnderTheNewRoot() throws Exception {
		LocalCaptureStorageAdapter adapter = new LocalCaptureStorageAdapter(temporaryDirectory.resolve("first"));
		Path secondRoot = temporaryDirectory.resolve("second");

		adapter.switchTo(secondRoot);
		StoredCapture stored = adapter.store("user@example.com", "file-1", "Report.pdf",
				new ByteArrayInputStream(new byte[] { 1 }));

		assertEquals(secondRoot, adapter.root());
		assertEquals(Path.of("user@example.com/file-1/Report.pdf"), stored.relativePath());
		assertEquals(secondRoot.resolve("user@example.com/file-1/Report.pdf"), secondRoot.resolve(stored.relativePath()));
	}
}
