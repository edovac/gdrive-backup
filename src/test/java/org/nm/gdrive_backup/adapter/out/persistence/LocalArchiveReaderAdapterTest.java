package org.nm.gdrive_backup.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nm.gdrive_backup.domain.model.ArchiveManifest;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.ArchiveReader;
import org.nm.gdrive_backup.domain.model.ArchiveSession;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.RevisionMode;

class LocalArchiveReaderAdapterTest {

	private static final String TARGET = "archives/scope/archive-0001-full.zip";

	@TempDir
	Path temporaryDirectory;

	@Test
	void readsBackWhatTheSessionAdapterWrote() throws Exception {
		LocalBackupRoot root = new LocalBackupRoot(temporaryDirectory);
		ArchiveManifest manifest = new ArchiveManifest(DriveScope.personal("user@example.com"), ArchiveMode.FULL,
				RevisionMode.LATEST_ONLY, 1, null, Instant.parse("2026-09-20T09:00:00Z"), null, null, List.of(),
				List.of(new ArchiveManifest.ManifestFile("f1", false, "Report.pdf", List.of("p"), null,
						"application/pdf", false, "r1", "Docs/Report.pdf", 5L, null)),
				List.of());
		try (ArchiveSession session = new LocalArchiveSessionAdapter(root).open(TARGET)) {
			session.writeEntry("Docs/Report.pdf", new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
			session.publish(manifest);
		}

		try (ArchiveReader reader = new LocalArchiveReaderAdapter(root).open(TARGET)) {
			assertEquals(manifest, reader.manifest());
			try (var content = reader.openEntry("Docs/Report.pdf")) {
				assertEquals("hello", new String(content.readAllBytes(), StandardCharsets.UTF_8));
			}
			assertThrows(IOException.class, () -> reader.openEntry("nope"));
		}
	}

	@Test
	void aMissingFileIsReportedAsNoSuchFile() {
		LocalArchiveReaderAdapter adapter = new LocalArchiveReaderAdapter(new LocalBackupRoot(temporaryDirectory));

		assertThrows(NoSuchFileException.class, () -> adapter.open(TARGET));
	}

	@Test
	void aFileThatIsNotAZipIsRejected() throws Exception {
		Files.createDirectories(temporaryDirectory.resolve("archives/scope"));
		Files.writeString(temporaryDirectory.resolve(TARGET), "definitely not a zip");
		LocalArchiveReaderAdapter adapter = new LocalArchiveReaderAdapter(new LocalBackupRoot(temporaryDirectory));

		assertThrows(IOException.class, () -> adapter.open(TARGET));
	}

	@Test
	void aZipWithoutAManifestIsRejected() throws Exception {
		Files.createDirectories(temporaryDirectory.resolve("archives/scope"));
		try (var output = new ZipOutputStream(Files.newOutputStream(temporaryDirectory.resolve(TARGET)))) {
			output.putNextEntry(new ZipEntry("a.txt"));
			output.write(1);
			output.closeEntry();
		}
		LocalArchiveReaderAdapter adapter = new LocalArchiveReaderAdapter(new LocalBackupRoot(temporaryDirectory));

		IOException exception = assertThrows(IOException.class, () -> adapter.open(TARGET));

		assertEquals(true, exception.getMessage().contains("manifest.json"));
	}

	@Test
	void followsTheBackupRootAcrossASwitch() throws Exception {
		LocalBackupRoot root = new LocalBackupRoot(temporaryDirectory.resolve("first"));
		LocalArchiveReaderAdapter reader = new LocalArchiveReaderAdapter(root);
		Path second = temporaryDirectory.resolve("second");
		root.switchTo(second);
		try (ArchiveSession session = new LocalArchiveSessionAdapter(root).open(TARGET)) {
			session.publish(new ArchiveManifest(DriveScope.personal("u"), ArchiveMode.FULL, RevisionMode.LATEST_ONLY, 1,
					null, Instant.parse("2026-09-20T09:00:00Z"), null, null, List.of(), List.of(), List.of()));
		}

		try (ArchiveReader opened = reader.open(TARGET)) {
			assertEquals(1, opened.manifest().sequenceNumber());
		}
	}
}
