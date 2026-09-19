package org.nm.gdrive_backup.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.ArchiveManifest;
import org.nm.gdrive_backup.domain.model.ArchiveManifest.ManifestEvent;
import org.nm.gdrive_backup.domain.model.ArchiveManifest.ManifestFile;
import org.nm.gdrive_backup.domain.model.ArchiveManifest.ManifestSource;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.RevisionMode;

class ArchiveManifestJsonTest {

	@Test
	void anIncrementalManifestSurvivesAWriteAndReadRoundTrip() throws Exception {
		ArchiveManifest manifest = new ArchiveManifest(DriveScope.sharedDrive("drive-1"), ArchiveMode.INCREMENTAL,
				RevisionMode.LATEST_ONLY, 2, 1, Instant.parse("2026-09-20T08:15:00Z"), "1041", "1077", List.of(),
				List.of(
						new ManifestFile("1AbC", false, "Budget", List.of("0Fold", "0Other"), "drive-1",
								"application/vnd.google-apps.spreadsheet", false, "v58", "content/1AbC", 20480L,
								"application/pdf"),
						new ManifestFile("1XyZ", false, "Contract.pdf", List.of(), null, "application/pdf", true, null,
								null, null, null),
						ManifestFile.removed("1Gone")),
				List.of(new ManifestEvent("1XyZ", "rename", "Contract.pdf", "Contract (final).pdf",
						Instant.parse("2026-09-19T17:02:11Z")),
						new ManifestEvent("1Gone", "delete", null, null, Instant.parse("2026-09-19T18:00:40Z"))));

		assertEquals(manifest, roundTrip(manifest));
	}

	@Test
	void aMergedManifestKeepsItsSourceArchives() throws Exception {
		ArchiveManifest manifest = new ArchiveManifest(DriveScope.personal("user@example.com"), ArchiveMode.MERGED_FULL,
				RevisionMode.LATEST_ONLY, 4, null, Instant.parse("2026-09-20T09:00:00Z"), null, "1077",
				List.of(new ManifestSource(1, "archive-0001-full.zip"), new ManifestSource(2, "archive-0002-incremental.zip")),
				List.of(), List.of());

		ArchiveManifest read = roundTrip(manifest);

		assertEquals(manifest, read);
		assertNull(read.baseSequenceNumber());
	}

	@Test
	void absentKeysReadBackAsNullOrEmpty() throws Exception {
		String json = """
				{"format_version":1,"scope_key":"u@example.com","scope_type":"PERSONAL","mode":"FULL",
				 "revision_mode":"LATEST_ONLY","sequence_number":1,"created_at":"2026-09-20T09:00:00Z"}""";

		ArchiveManifest read = ArchiveManifestJson.read(json);

		assertNull(read.baseSequenceNumber());
		assertNull(read.fromPageToken());
		assertTrue(read.files().isEmpty());
		assertTrue(read.events().isEmpty());
		assertTrue(read.sourceArchives().isEmpty());
	}

	@Test
	void aMissingOrUnknownFormatVersionIsRefused() {
		String base = "\"scope_key\":\"u\",\"scope_type\":\"PERSONAL\",\"mode\":\"FULL\",\"revision_mode\":\"LATEST_ONLY\","
				+ "\"sequence_number\":1,\"created_at\":\"2026-09-20T09:00:00Z\"";

		IOException missing = assertThrows(IOException.class, () -> ArchiveManifestJson.read("{" + base + "}"));
		IOException unknown = assertThrows(IOException.class,
				() -> ArchiveManifestJson.read("{\"format_version\":2," + base + "}"));

		assertTrue(missing.getMessage().contains("format_version"));
		assertTrue(unknown.getMessage().contains("2"));
	}

	@Test
	void malformedOrIncompleteJsonBecomesAnIoException() {
		assertThrows(IOException.class, () -> ArchiveManifestJson.read("not json"));
		assertThrows(IOException.class, () -> ArchiveManifestJson.read(""));
		assertThrows(IOException.class, () -> ArchiveManifestJson.read("{\"format_version\":1,\"mode\":\"FULL\"}"));
		assertThrows(IOException.class, () -> ArchiveManifestJson.read(
				"{\"format_version\":1,\"scope_key\":\"u\",\"scope_type\":\"NOPE\",\"mode\":\"FULL\","
						+ "\"revision_mode\":\"LATEST_ONLY\",\"sequence_number\":1,\"created_at\":\"2026-09-20T09:00:00Z\"}"));
	}

	private static ArchiveManifest roundTrip(ArchiveManifest manifest) throws IOException {
		StringWriter writer = new StringWriter();
		ArchiveManifestJson.write(manifest, writer);
		return ArchiveManifestJson.read(writer.toString());
	}
}
