package org.nm.gdrive_backup.adapter.out.google;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.api.services.drive.model.File;
import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.DriveItem;
import org.nm.gdrive_backup.domain.model.StoredFile;

class GoogleDriveAdapterTest {

	@Test
	void mapsGoogleFilesIntoDomainItems() {
		File folder = new File();
		folder.setId("folder-1");
		folder.setName("Team Docs");
		folder.setMimeType("application/vnd.google-apps.folder");
		folder.setDriveId("drive-99");
		folder.setTrashed(false);

		File doc = new File();
		doc.setId("file-2");
		doc.setName("Budget.xlsx");
		doc.setMimeType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
		doc.setDriveId("drive-99");
		doc.setTrashed(false);

		List<DriveItem> items = GoogleDriveAdapter.mapFiles(List.of(folder, doc));

		assertEquals(2, items.size());
		assertEquals("folder-1", items.getFirst().id());
		assertTrue(items.getFirst().folder());
		assertEquals("Budget.xlsx", items.get(1).name());
		assertFalse(items.get(1).folder());
	}

	@Test
	void aBinaryFileKeepsItsHeadRevisionIdAsTheContentRevision() {
		File pdf = file("file-1", "application/pdf");
		pdf.setHeadRevisionId("head-7");
		pdf.setVersion(42L);

		StoredFile stored = GoogleDriveAdapter.mapStoredFile(pdf, "user@example.com");

		assertEquals("head-7", stored.headRevisionId());
	}

	@Test
	void anOrdinaryFilesSizeIsCarriedOverFromDrive() {
		File pdf = file("file-1", "application/pdf");
		pdf.setHeadRevisionId("head-7");
		pdf.setSize(83_886_080L);

		StoredFile stored = GoogleDriveAdapter.mapStoredFile(pdf, "user@example.com");

		assertEquals(83_886_080L, stored.sizeBytes());
	}

	@Test
	void aGoogleNativeFileHasNoSize() {
		File doc = file("file-2", "application/vnd.google-apps.document");
		doc.setVersion(42L);

		org.junit.jupiter.api.Assertions.assertNull(GoogleDriveAdapter.mapStoredFile(doc, "user@example.com").sizeBytes());
	}

	@Test
	void aGoogleNativeFileWithoutAHeadRevisionUsesItsVersionInstead() {
		File doc = file("file-2", "application/vnd.google-apps.document");
		doc.setVersion(42L);

		StoredFile stored = GoogleDriveAdapter.mapStoredFile(doc, "user@example.com");

		assertEquals("v42", stored.headRevisionId());
	}

	@Test
	void aFileWithNeitherHasNoContentRevision() {
		StoredFile stored = GoogleDriveAdapter.mapStoredFile(file("folder-1", "application/vnd.google-apps.folder"),
				"user@example.com");

		org.junit.jupiter.api.Assertions.assertNull(stored.headRevisionId());
	}

	private static File file(String id, String mimeType) {
		File file = new File();
		file.setId(id);
		file.setName(id);
		file.setMimeType(mimeType);
		file.setTrashed(false);
		return file;
	}
}
