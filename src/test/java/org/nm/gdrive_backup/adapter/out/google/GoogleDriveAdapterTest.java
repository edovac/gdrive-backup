package org.nm.gdrive_backup.adapter.out.google;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.api.services.drive.model.File;
import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.DriveItem;

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
}
