package org.nm.gdrive_backup.adapter.out.google;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.api.services.drive.model.Change;
import com.google.api.services.drive.model.File;
import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.DriveChange;
import org.nm.gdrive_backup.domain.model.DriveItem;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.PersonalDriveContent;
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

	@Test
	void aPersonalDriveListsOnlyOwnedFilesUnlessSharedOnesAreIncluded() {
		DriveScope personal = DriveScope.personal("user@example.com");

		assertEquals("trashed = false and 'me' in owners",
				GoogleDriveAdapter.listQuery(personal, PersonalDriveContent.OWNED_ONLY));
		assertEquals("trashed = false", GoogleDriveAdapter.listQuery(personal, PersonalDriveContent.ALL_ACCESSIBLE));
	}

	@Test
	void aSharedDriveIsListedWholeWhateverThePersonalDriveContent() {
		DriveScope shared = DriveScope.sharedDrive("drive-1");

		assertEquals("trashed = false", GoogleDriveAdapter.listQuery(shared, PersonalDriveContent.OWNED_ONLY));
		assertFalse(GoogleDriveAdapter.ownedOnly(shared, PersonalDriveContent.OWNED_ONLY));
	}

	@Test
	void aChangeToAFileTheUserDoesNotOwnIsOutOfScopeForOwnedOnly() {
		DriveScope personal = DriveScope.personal("user@example.com");
		File shared = file("file-1", "application/pdf");
		shared.setOwnedByMe(false);

		DriveChange change = GoogleDriveAdapter.mapChange(change("file-1", shared), personal,
				PersonalDriveContent.OWNED_ONLY);

		assertEquals(DriveChange.outOfScope("file-1"), change);
	}

	@Test
	void aChangeToASharedDriveItemIsOutOfScopeForOwnedOnly() {
		File inSharedDrive = file("file-1", "application/pdf");
		inSharedDrive.setDriveId("drive-1");

		DriveChange change = GoogleDriveAdapter.mapChange(change("file-1", inSharedDrive),
				DriveScope.personal("user@example.com"), PersonalDriveContent.OWNED_ONLY);

		assertTrue(change.outOfScope());
	}

	@Test
	void aChangeToAnOwnedFileIsKept() {
		File owned = file("file-1", "application/pdf");
		owned.setOwnedByMe(true);

		DriveChange change = GoogleDriveAdapter.mapChange(change("file-1", owned),
				DriveScope.personal("user@example.com"), PersonalDriveContent.OWNED_ONLY);

		assertFalse(change.outOfScope());
		assertEquals("file-1", change.file().fileId());
	}

	@Test
	void aChangeToASharedFileIsKeptWhenSharedFilesAreIncluded() {
		File shared = file("file-1", "application/pdf");
		shared.setOwnedByMe(false);

		DriveChange change = GoogleDriveAdapter.mapChange(change("file-1", shared),
				DriveScope.personal("user@example.com"), PersonalDriveContent.ALL_ACCESSIBLE);

		assertFalse(change.outOfScope());
		assertEquals("file-1", change.file().fileId());
	}

	@Test
	void aRemovalStaysARemovalEvenForOwnedOnly() {
		Change removal = new Change().setFileId("file-1").setRemoved(true);

		DriveChange change = GoogleDriveAdapter.mapChange(removal, DriveScope.personal("user@example.com"),
				PersonalDriveContent.OWNED_ONLY);

		assertEquals(new DriveChange("file-1", true, null), change);
	}

	@Test
	void aSharedDrivesChangesAreNeverOutOfScope() {
		File item = file("file-1", "application/pdf");
		item.setDriveId("drive-1");
		item.setOwnedByMe(false);

		DriveChange change = GoogleDriveAdapter.mapChange(change("file-1", item), DriveScope.sharedDrive("drive-1"),
				PersonalDriveContent.OWNED_ONLY);

		assertFalse(change.outOfScope());
		assertEquals("drive-1", change.file().ownerScope());
	}

	private static Change change(String fileId, File file) {
		return new Change().setFileId(fileId).setRemoved(false).setFile(file);
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
