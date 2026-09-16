package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.StoredFile;

class FlatTreePathResolverTest {

	private static final String FOLDER = "application/vnd.google-apps.folder";
	private static final String FILE = "text/plain";

	@Test
	void resolvesNestedFolderPaths() {
		StoredFile folderA = folder("folder-A", "A", "");
		StoredFile folderB = folder("folder-B", "B", "folder-A");
		StoredFile file = file("file-1", "Report.pdf", "folder-B");
		FlatTreePathResolver resolver = resolverFor(folderA, folderB, file);

		assertEquals("A/B/Report.pdf", resolver.resolveEntryName(file));
	}

	@Test
	void unresolvableParentPlacesFileAtArchiveRoot() {
		StoredFile file = file("file-1", "Report.pdf", "unknown-parent-id");
		FlatTreePathResolver resolver = resolverFor(file);

		assertEquals("Report.pdf", resolver.resolveEntryName(file));
	}

	@Test
	void noParentsPlacesFileAtArchiveRoot() {
		StoredFile file = file("file-1", "Report.pdf", "");
		FlatTreePathResolver resolver = resolverFor(file);

		assertEquals("Report.pdf", resolver.resolveEntryName(file));
	}

	@Test
	void multiParentFileUsesOnlyTheFirstParent() {
		StoredFile folderA = folder("folder-A", "A", "");
		StoredFile folderB = folder("folder-B", "B", "");
		StoredFile file = file("file-1", "Report.pdf", "folder-A,folder-B");
		FlatTreePathResolver resolver = resolverFor(folderA, folderB, file);

		assertEquals("A/Report.pdf", resolver.resolveEntryName(file));
	}

	@Test
	void aDeepChainIsCappedRatherThanStackOverflowing() {
		StoredFile[] chain = new StoredFile[150];
		chain[0] = folder("folder-0", "folder-0", "");
		for (int i = 1; i < chain.length; i++) {
			chain[i] = folder("folder-" + i, "folder-" + i, "folder-" + (i - 1));
		}
		StoredFile file = file("file-1", "Report.pdf", "folder-149");
		FlatTreePathResolver resolver = resolverFor(concat(chain, file));

		String path = resolver.resolveEntryName(file);

		assertNotNull(path);
		int segments = path.split("/").length;
		assertTrue(segments < chain.length, "expected the depth cap to truncate the path, got " + segments + " segments");
	}

	@Test
	void aParentCycleTerminatesInsteadOfLoopingForever() {
		StoredFile folderX = folder("folder-X", "X", "folder-Y");
		StoredFile folderY = folder("folder-Y", "Y", "folder-X");
		StoredFile file = file("file-1", "Report.pdf", "folder-X");
		FlatTreePathResolver resolver = resolverFor(folderX, folderY, file);

		assertNotNull(resolver.resolveEntryName(file));
	}

	@Test
	void aTrashedFolderStillContributesItsNameToALiveDescendant() {
		StoredFile trashedFolder = new StoredFile("folder-A", "user@example.com", "Old", "", null, FOLDER, true, null, null);
		StoredFile file = file("file-1", "Report.pdf", "folder-A");
		FlatTreePathResolver resolver = resolverFor(trashedFolder, file);

		assertEquals("Old/Report.pdf", resolver.resolveEntryName(file));
	}

	@Test
	void collidingFolderNamesUnderTheSameParentAreDisambiguated() {
		// Siblings are disambiguated in a fixed order (by fileId), not by lookup order:
		// "folder-A" sorts before "folder-B", so it keeps the base name.
		StoredFile folder1 = folder("folder-A", "Report", "");
		StoredFile folder2 = folder("folder-B", "Report", "");
		StoredFile fileInFirst = file("file-1", "One.pdf", "folder-A");
		StoredFile fileInSecond = file("file-2", "Two.pdf", "folder-B");
		FlatTreePathResolver resolver = resolverFor(folder1, folder2, fileInFirst, fileInSecond);

		assertEquals("Report/One.pdf", resolver.resolveEntryName(fileInFirst));
		assertEquals("Report (2)/Two.pdf", resolver.resolveEntryName(fileInSecond));
	}

	@Test
	void aFolderAndAFileCanCollideByNameAndAreDisambiguated() {
		// "file-1" sorts before "folder-A", so the top-level file keeps the base name and
		// the folder is suffixed, even though a filesystem doesn't distinguish the two kinds.
		StoredFile folder = folder("folder-A", "Report", "");
		StoredFile fileNamedSame = file("file-1", "Report", "");
		StoredFile fileInsideFolder = file("file-2", "Inner.pdf", "folder-A");
		FlatTreePathResolver resolver = resolverFor(folder, fileNamedSame, fileInsideFolder);

		assertEquals("Report", resolver.resolveEntryName(fileNamedSame));
		assertEquals("Report (2)/Inner.pdf", resolver.resolveEntryName(fileInsideFolder));
	}

	@Test
	void sanitizerIsAppliedToEveryPathSegment() {
		StoredFile folder = folder("folder-A", "Q1: Finance", "");
		StoredFile file = file("file-1", "Report <final>.pdf", "folder-A");
		FlatTreePathResolver resolver = resolverFor(folder, file);

		assertEquals("Q1_ Finance/Report _final_.pdf", resolver.resolveEntryName(file));
	}

	private static FlatTreePathResolver resolverFor(StoredFile... files) {
		Map<String, StoredFile> byId = new HashMap<>();
		for (StoredFile file : files) {
			byId.put(file.fileId(), file);
		}
		return new FlatTreePathResolver(byId);
	}

	private static StoredFile[] concat(StoredFile[] chain, StoredFile file) {
		StoredFile[] all = new StoredFile[chain.length + 1];
		System.arraycopy(chain, 0, all, 0, chain.length);
		all[chain.length] = file;
		return all;
	}

	private static StoredFile folder(String fileId, String name, String parents) {
		return new StoredFile(fileId, "user@example.com", name, parents, null, FOLDER, false, null, null);
	}

	private static StoredFile file(String fileId, String name, String parents) {
		return new StoredFile(fileId, "user@example.com", name, parents, null, FILE, false, "revision-1", 1L);
	}
}
