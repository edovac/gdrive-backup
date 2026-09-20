package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.DriveScopeType;
import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveChainException;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.RevisionMode;

class ArchiveChainResolverTest {

	@Test
	void followsBaseLinksFromTheLatestArchiveDownToTheRoot() {
		Archive full = archive(1, 1, null, ArchiveMode.FULL);
		Archive first = archive(2, 2, 1L, ArchiveMode.INCREMENTAL);
		Archive second = archive(3, 3, 2L, ArchiveMode.INCREMENTAL);

		List<Archive> chain = ArchiveChainResolver.currentChain(List.of(second, full, first));

		assertEquals(List.of(full, first, second), chain);
	}

	@Test
	void afterAMergeTheMergedFullIsTheRootAndOlderArchivesAreIgnored() {
		Archive oldFull = archive(1, 1, null, ArchiveMode.FULL);
		Archive oldIncremental = archive(2, 2, 1L, ArchiveMode.INCREMENTAL);
		Archive merged = archive(3, 3, null, ArchiveMode.MERGED_FULL);
		Archive next = archive(4, 4, 3L, ArchiveMode.INCREMENTAL);

		List<Archive> chain = ArchiveChainResolver.currentChain(List.of(oldFull, oldIncremental, merged, next));

		assertEquals(List.of(merged, next), chain);
	}

	@Test
	void aBaseLinkPointingAtNoRecordIsAGap() {
		Archive first = archive(2, 2, 99L, ArchiveMode.INCREMENTAL);

		ArchiveChainException exception = assertThrows(ArchiveChainException.class,
				() -> ArchiveChainResolver.currentChain(List.of(first)));

		assertEquals(true, exception.getMessage().contains("missing"));
	}

	@Test
	void aScopeWithNoArchivesHasNothingToMerge() {
		assertThrows(ArchiveChainException.class, () -> ArchiveChainResolver.currentChain(List.of()));
	}

	@Test
	void aRootWithNoIncrementalsHasNothingToMerge() {
		ArchiveChainException exception = assertThrows(ArchiveChainException.class,
				() -> ArchiveChainResolver.currentChain(List.of(archive(1, 1, null, ArchiveMode.FULL))));

		assertEquals(true, exception.getMessage().contains("Nothing to merge"));
	}

	@Test
	void aLoopingChainIsRejectedInsteadOfSpinning() {
		Archive a = archive(1, 1, 2L, ArchiveMode.INCREMENTAL);
		Archive b = archive(2, 2, 1L, ArchiveMode.INCREMENTAL);

		assertThrows(ArchiveChainException.class, () -> ArchiveChainResolver.currentChain(List.of(a, b)));
	}

	private static Archive archive(long id, int sequenceNumber, Long baseId, ArchiveMode mode) {
		return new Archive(id, "user@example.com", DriveScopeType.PERSONAL, sequenceNumber, baseId, mode, RevisionMode.LATEST_ONLY, Instant.now(),
				"archives/x/archive-000" + sequenceNumber + ".zip", null, null, false);
	}
}
