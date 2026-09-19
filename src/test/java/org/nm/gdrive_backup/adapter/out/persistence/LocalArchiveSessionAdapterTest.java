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
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.RevisionMode;

class LocalArchiveSessionAdapterTest {

	private static final String TARGET = "archives/scope/archive-0001-full.zip";

	@TempDir
	Path temporaryDirectory;

	@Test
	void streamsEntriesThenEmbedsTheManifestAndPublishesAtTheTargetPath() throws Exception {
		LocalArchiveSessionAdapter adapter = new LocalArchiveSessionAdapter(new LocalBackupRoot(temporaryDirectory));
		ArchiveManifest manifest = new ArchiveManifest(DriveScope.personal("user@example.com"), ArchiveMode.FULL,
				RevisionMode.LATEST_ONLY, 1, null, Instant.parse("2026-09-16T10:00:00Z"), null, null, List.of(),
				List.of(new ArchiveManifest.ManifestFile("file-1", false, "Report.pdf", List.of("folder-1"), null,
						"application/pdf", false, "revision-1", "Docs/Report.pdf", 14L, null)),
				List.of());

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

	@Test
	void writesAnIncrementalManifestInTheDocumentedFormat() throws Exception {
		LocalArchiveSessionAdapter adapter = new LocalArchiveSessionAdapter(new LocalBackupRoot(temporaryDirectory));
		ArchiveManifest manifest = new ArchiveManifest(DriveScope.sharedDrive("drive-1"), ArchiveMode.INCREMENTAL,
				RevisionMode.LATEST_ONLY, 2, 1, Instant.parse("2026-09-20T08:15:00Z"), "1041", "1077", List.of(),
				List.of(
						new ArchiveManifest.ManifestFile("1AbC", false, "Budget 2026", List.of("0Fold", "0Other"),
								"drive-1", "application/vnd.google-apps.spreadsheet", false, "v58", "content/1AbC",
								20480L, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
						new ArchiveManifest.ManifestFile("1XyZ", false, "Contract.pdf", List.of(), null,
								"application/pdf", true, null, null, null, null),
						ArchiveManifest.ManifestFile.removed("1Gone")),
				List.of(new ArchiveManifest.ManifestEvent("1Gone", "delete", null, null,
						Instant.parse("2026-09-19T18:00:40Z"))));

		try (ArchiveSession session = adapter.open(TARGET)) {
			session.publish(manifest);
		}

		com.google.gson.JsonObject json = readManifest();
		assertEquals(1, json.get("format_version").getAsInt());
		assertEquals("drive-1", json.get("scope_key").getAsString());
		assertEquals("SHARED_DRIVE", json.get("scope_type").getAsString());
		assertEquals("INCREMENTAL", json.get("mode").getAsString());
		assertEquals(2, json.get("sequence_number").getAsInt());
		assertEquals(1, json.get("base_sequence_number").getAsInt());
		assertEquals("1041", json.get("from_page_token").getAsString());
		assertEquals("1077", json.get("to_page_token").getAsString());
		assertFalse(json.has("source_archives"), "empty source list is omitted");
		assertFalse(json.has("base_archive_id"));

		com.google.gson.JsonArray files = json.getAsJsonArray("files");
		com.google.gson.JsonObject sheet = files.get(0).getAsJsonObject();
		assertEquals("1AbC", sheet.get("file_id").getAsString());
		assertEquals("Budget 2026", sheet.get("name").getAsString());
		assertEquals("0Fold", sheet.getAsJsonArray("parents").get(0).getAsString());
		assertEquals("0Other", sheet.getAsJsonArray("parents").get(1).getAsString());
		assertEquals("drive-1", sheet.get("drive_id").getAsString());
		assertEquals("v58", sheet.get("revision_id").getAsString());
		assertEquals("content/1AbC", sheet.get("entry").getAsString());
		assertEquals(20480, sheet.get("size_bytes").getAsLong());
		assertTrue(sheet.has("export_mime_type"));
		assertFalse(sheet.get("trashed").getAsBoolean());
		assertFalse(sheet.has("removed"));

		com.google.gson.JsonObject metadataOnly = files.get(1).getAsJsonObject();
		assertTrue(metadataOnly.get("trashed").getAsBoolean());
		assertFalse(metadataOnly.has("entry"));
		assertFalse(metadataOnly.has("revision_id"));
		assertFalse(metadataOnly.has("size_bytes"));
		assertFalse(metadataOnly.has("export_mime_type"));
		assertEquals(0, metadataOnly.getAsJsonArray("parents").size());

		com.google.gson.JsonObject removed = files.get(2).getAsJsonObject();
		assertEquals(java.util.Set.of("file_id", "removed"), removed.keySet());
		assertTrue(removed.get("removed").getAsBoolean());

		com.google.gson.JsonObject event = json.getAsJsonArray("events").get(0).getAsJsonObject();
		assertEquals(java.util.Set.of("file_id", "event_type", "timestamp"), event.keySet());
	}

	@Test
	void aFullManifestOmitsChainAndTokenKeys() throws Exception {
		LocalArchiveSessionAdapter adapter = new LocalArchiveSessionAdapter(new LocalBackupRoot(temporaryDirectory));

		try (ArchiveSession session = adapter.open(TARGET)) {
			session.publish(emptyManifest());
		}

		com.google.gson.JsonObject json = readManifest();
		assertEquals("PERSONAL", json.get("scope_type").getAsString());
		assertFalse(json.has("base_sequence_number"));
		assertFalse(json.has("from_page_token"));
		assertFalse(json.has("to_page_token"));
		assertEquals(0, json.getAsJsonArray("files").size());
		assertEquals(0, json.getAsJsonArray("events").size());
	}

	@Test
	void aMergedManifestListsItsSourceArchives() throws Exception {
		LocalArchiveSessionAdapter adapter = new LocalArchiveSessionAdapter(new LocalBackupRoot(temporaryDirectory));
		ArchiveManifest manifest = new ArchiveManifest(DriveScope.personal("user@example.com"),
				ArchiveMode.MERGED_FULL, RevisionMode.LATEST_ONLY, 4, null, Instant.parse("2026-09-20T09:00:00Z"), null,
				"1077", List.of(new ArchiveManifest.ManifestSource(1, "archive-0001-full.zip"),
						new ArchiveManifest.ManifestSource(2, "archive-0002-incremental.zip")),
				List.of(), List.of());

		try (ArchiveSession session = adapter.open(TARGET)) {
			session.publish(manifest);
		}

		com.google.gson.JsonObject json = readManifest();
		assertEquals("MERGED_FULL", json.get("mode").getAsString());
		com.google.gson.JsonArray sources = json.getAsJsonArray("source_archives");
		assertEquals(2, sources.size());
		assertEquals(2, sources.get(1).getAsJsonObject().get("sequence_number").getAsInt());
		assertEquals("archive-0002-incremental.zip", sources.get(1).getAsJsonObject().get("file_name").getAsString());
	}

	private com.google.gson.JsonObject readManifest() throws Exception {
		try (ZipFile zip = new ZipFile(temporaryDirectory.resolve(TARGET).toFile())) {
			String text = new String(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes(),
					StandardCharsets.UTF_8);
			return com.google.gson.JsonParser.parseString(text).getAsJsonObject();
		}
	}

	private void assertOnlyTheArchiveRemains() throws Exception {
		try (var files = Files.list(temporaryDirectory.resolve("archives/scope"))) {
			List<Path> remaining = files.toList();
			assertEquals(1, remaining.size());
			assertEquals("archive-0001-full.zip", remaining.getFirst().getFileName().toString());
		}
	}

	private static ArchiveManifest emptyManifest() {
		return new ArchiveManifest(DriveScope.personal("user@example.com"), ArchiveMode.FULL, RevisionMode.LATEST_ONLY, 1,
				null, Instant.parse("2026-09-16T10:00:00Z"), null, null, List.of(), List.of(), List.of());
	}
}
