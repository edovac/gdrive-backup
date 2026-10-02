package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.StoredFile;

class DrivePathResolverTest {

	private final Map<String, StoredFile> files = new HashMap<>();
	private final AtomicInteger lookups = new AtomicInteger();
	private final DrivePathResolver resolver = new DrivePathResolver("My Drive", id -> {
		lookups.incrementAndGet();
		return files.get(id);
	});

	@Test
	void buildsTheFullPathFromTheFolderChain() {
		add("f1", "Reports", "root-id");
		add("f2", "2026", "f1");
		StoredFile report = add("file-1", "Q3.pdf", "f2");

		assertEquals("My Drive/Reports/2026/Q3.pdf", resolver.pathOf(report));
	}

	@Test
	void aFileAtTheTopLevelSitsDirectlyUnderTheDriveLabel() {
		assertEquals("My Drive/Notes.txt", resolver.pathOf(add("file-1", "Notes.txt", "root-id")));
		assertEquals("My Drive/Loose.txt", resolver.pathOf(add("file-2", "Loose.txt", "")));
		assertEquals("My Drive/NoParents.txt", resolver.pathOf(
				new StoredFile("file-3", "u", "NoParents.txt", null, null, "text/plain", false, "r", null)));
	}

	@Test
	void aParentThatIsNotKnownIsTheDriveRoot() {
		add("f1", "Reports", "root-id");

		// "root-id" is the drive's own root folder, which Drive never lists as a file.
		assertEquals("My Drive/Reports/Q3.pdf", resolver.pathOf(add("file-1", "Q3.pdf", "f1")));
	}

	@Test
	void keepsTheRealNamesWithoutSanitizingOrDisambiguating() {
		add("f1", "Q3: plan?", "root-id");
		add("f2", "Q3: plan?", "root-id");
		StoredFile first = add("file-1", "a/b.pdf", "f1");
		StoredFile second = add("file-2", "a/b.pdf", "f2");

		assertEquals("My Drive/Q3: plan?/a/b.pdf", resolver.pathOf(first));
		assertEquals("My Drive/Q3: plan?/a/b.pdf", resolver.pathOf(second));
	}

	@Test
	void aFileWithSeveralParentsUsesTheFirst() {
		add("f1", "First", "root-id");
		add("f2", "Second", "root-id");

		assertEquals("My Drive/First/x.pdf", resolver.pathOf(add("file-1", "x.pdf", "f1,f2")));
	}

	@Test
	void aSharedDrivesPathsStartWithItsNameOrFallBackToItsId() {
		assertEquals("Finance", DrivePathResolver.rootLabel(DriveScope.sharedDrive("drive-1"), "Finance"));
		assertEquals("drive-1", DrivePathResolver.rootLabel(DriveScope.sharedDrive("drive-1"), null));
		assertEquals("drive-1", DrivePathResolver.rootLabel(DriveScope.sharedDrive("drive-1"), " "));
		assertEquals("My Drive", DrivePathResolver.rootLabel(DriveScope.personal("user@example.com"), "ignored"));
		assertEquals("My Drive", DrivePathResolver.rootLabel(DriveScope.personal("user@example.com"), null));
	}

	@Test
	void aCyclicChainIsCutWithALeadingEllipsisInsteadOfLooping() {
		add("f1", "A", "f2");
		add("f2", "B", "f1");

		assertEquals("…/B/A/x.pdf", resolver.pathOf(add("file-1", "x.pdf", "f1")));
	}

	@Test
	void aChainDeeperThanTheCapIsCut() {
		int depth = 150;
		for (int i = 0; i < depth; i++) {
			add("d" + i, "n" + i, i == depth - 1 ? "root-id" : "d" + (i + 1));
		}

		String path = resolver.pathOf(add("file-1", "x.pdf", "d0"));

		assertEquals(true, path.startsWith("…/"), path);
		assertEquals(true, path.endsWith("/n1/n0/x.pdf"), path);
	}

	@Test
	void usesTheFileIdWhenANameIsMissing() {
		add("f1", null, "root-id");

		assertEquals("My Drive/f1/file-1", resolver.pathOf(add("file-1", " ", "f1")));
	}

	@Test
	void looksEachFolderUpOnceAcrossFiles() {
		add("f1", "Reports", "root-id");
		add("f2", "2026", "f1");

		resolver.pathOf(add("file-1", "a.pdf", "f2"));
		int afterFirst = lookups.get();
		assertEquals("My Drive/Reports/2026/b.pdf", resolver.pathOf(add("file-2", "b.pdf", "f2")));
		assertEquals("My Drive/Reports/c.pdf", resolver.pathOf(add("file-3", "c.pdf", "f1")));

		assertEquals(afterFirst, lookups.get(), "known folders come from the cache, not from another lookup");
	}

	@Test
	void resolvesFromSeveralThreadsAtOnce() throws Exception {
		add("f1", "Reports", "root-id");
		StoredFile[] many = new StoredFile[200];
		for (int i = 0; i < many.length; i++) {
			many[i] = add("file-" + i, "F" + i + ".pdf", "f1");
		}
		var executor = java.util.concurrent.Executors.newFixedThreadPool(8);
		try {
			var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
			for (StoredFile file : many) {
				futures.add(executor.submit(() -> resolver.pathOf(file)));
			}
			for (int i = 0; i < many.length; i++) {
				assertEquals("My Drive/Reports/F" + i + ".pdf", futures.get(i).get());
			}
		} finally {
			executor.shutdownNow();
		}
	}

	private StoredFile add(String id, String name, String parents) {
		StoredFile file = new StoredFile(id, "user@example.com", name, parents, null, "application/pdf", false, "r1", null);
		files.put(id, file);
		return file;
	}
}
