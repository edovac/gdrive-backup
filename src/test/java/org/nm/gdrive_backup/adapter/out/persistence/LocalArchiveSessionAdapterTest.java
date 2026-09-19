package org.nm.gdrive_backup.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nm.gdrive_backup.domain.model.ArchiveManifest;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.ArchiveSession;
import org.nm.gdrive_backup.domain.model.RevisionMode;

class LocalArchiveSessionAdapterTest {

	private static final String TARGET = "archives/scope/archive-0001-full.zip";

	@TempDir
	Path temporaryDirectory;

	@Test
	void streamsEntriesThenEmbedsTheManifestAndPublishesAtTheTargetPath() throws Exception {
		LocalArchiveSessionAdapter adapter = new LocalArchiveSessionAdapter(new LocalBackupRoot(temporaryDirectory));
		ArchiveManifest manifest = new ArchiveManifest("user@example.com", ArchiveMode.FULL, RevisionMode.LATEST_ONLY,
				1, null, Instant.parse("2026-09-16T10:00:00Z"), null, null,
				List.of(new ArchiveManifest.ManifestFile("file-1", "Docs/Report.pdf", "revision-1", 14)), List.of());

		try (ArchiveSession session = adapter.open(TARGET)) {
			long written = session.writeEntry("Docs/Report.pdf",
					new ByteArrayInputStream("report-content".getBytes(StandardCharsets.UTF_8)));
			assertEquals(14, written);
			assertTrue(session.containsEntry("Docs/Report.pdf"));
			assertFalse(session.containsEntry("other"));
			assertFalse(Files.exists(temporaryDirectory.resolve(TARGET)), "nothing is visible before publish");

			session.publish(manifest);
		}

		try (ZipFile zip = new ZipFile(temporaryDirectory.resolve(TARGET).toFile())) {
			ZipEntry report = zip.getEntry("Docs/Report.pdf");
			assertNotNull(report);
			assertEquals("report-content", new String(zip.getInputStream(report).readAllBytes(), StandardCharsets.UTF_8));
			String manifestJson = new String(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes(),
					StandardCharsets.UTF_8);
			assertTrue(manifestJson.contains("\"scope_key\": \"user@example.com\""));
			assertTrue(manifestJson.contains("\"sequence_number\": 1"));
			assertTrue(manifestJson.contains("\"file_id\": \"file-1\""));
		}
		assertOnlyTheArchiveRemains();
	}

	@Test
	void createsMissingParentDirectories() throws Exception {
		LocalArchiveSessionAdapter adapter = new LocalArchiveSessionAdapter(new LocalBackupRoot(temporaryDirectory));

		try (ArchiveSession session = adapter.open("archives/deeply/nested/scope/archive-0001-full.zip")) {
			session.publish(emptyManifest());
		}

		assertTrue(Files.exists(temporaryDirectory.resolve("archives/deeply/nested/scope/archive-0001-full.zip")));
	}

	@Test
	void closingWithoutPublishingLeavesNoFileAtAll() throws Exception {
		LocalArchiveSessionAdapter adapter = new LocalArchiveSessionAdapter(new LocalBackupRoot(temporaryDirectory));

		try (ArchiveSession session = adapter.open(TARGET)) {
			session.writeEntry("a", new ByteArrayInputStream(new byte[] { 1 }));
		}

		try (var files = Files.list(temporaryDirectory.resolve("archives/scope"))) {
			assertEquals(0, files.count());
		}
	}

	@Test
	void discardingDeletesTheStagedFileAndCanBeRepeated() throws Exception {
		LocalArchiveSessionAdapter adapter = new LocalArchiveSessionAdapter(new LocalBackupRoot(temporaryDirectory));
		ArchiveSession session = adapter.open(TARGET);
		session.writeEntry("a", new ByteArrayInputStream(new byte[] { 1 }));

		session.discard();
		session.discard();
		session.close();

		assertFalse(Files.exists(temporaryDirectory.resolve(TARGET)));
		try (var files = Files.list(temporaryDirectory.resolve("archives/scope"))) {
			assertEquals(0, files.count());
		}
	}

	@Test
	void aFailedStreamLeavesNoPartialArchiveOnceTheSessionCloses() throws Exception {
		LocalArchiveSessionAdapter adapter = new LocalArchiveSessionAdapter(new LocalBackupRoot(temporaryDirectory));
		java.io.InputStream failing = new java.io.InputStream() {
			@Override
			public int read() throws java.io.IOException {
				throw new java.io.IOException("connection reset");
			}
		};

		try (ArchiveSession session = adapter.open(TARGET)) {
			org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> session.writeEntry("a", failing));
		}

		assertFalse(Files.exists(temporaryDirectory.resolve(TARGET)));
		try (var files = Files.list(temporaryDirectory.resolve("archives/scope"))) {
			assertEquals(0, files.count());
		}
	}

	@Test
	void readsTheBackupRootFreshAcrossASwitch() throws Exception {
		LocalBackupRoot root = new LocalBackupRoot(temporaryDirectory.resolve("first"));
		LocalArchiveSessionAdapter adapter = new LocalArchiveSessionAdapter(root);
		Path secondRoot = temporaryDirectory.resolve("second");
		root.switchTo(secondRoot);

		try (ArchiveSession session = adapter.open(TARGET)) {
			session.publish(emptyManifest());
		}

		assertTrue(Files.exists(secondRoot.resolve(TARGET)));
		assertFalse(Files.exists(temporaryDirectory.resolve("first").resolve(TARGET)));
	}

	private void assertOnlyTheArchiveRemains() throws Exception {
		try (var files = Files.list(temporaryDirectory.resolve("archives/scope"))) {
			List<Path> remaining = files.toList();
			assertEquals(1, remaining.size());
			assertEquals("archive-0001-full.zip", remaining.getFirst().getFileName().toString());
		}
	}

	private static ArchiveManifest emptyManifest() {
		return new ArchiveManifest("user@example.com", ArchiveMode.FULL, RevisionMode.LATEST_ONLY, 1, null,
				Instant.parse("2026-09-16T10:00:00Z"), null, null, List.of(), List.of());
	}
}
