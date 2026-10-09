package org.nm.gdrive_backup.adapter.in.javafx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.ArchiveState;
import org.nm.gdrive_backup.domain.model.ArchiveView;
import org.nm.gdrive_backup.domain.model.DeletionPlan;
import org.nm.gdrive_backup.domain.model.DeletionResult;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.DriveScopeType;
import org.nm.gdrive_backup.domain.model.LostContent;
import org.nm.gdrive_backup.domain.model.MergeResult;
import org.nm.gdrive_backup.domain.model.ObsoleteArchive;
import org.nm.gdrive_backup.domain.model.RevisionMode;
import org.nm.gdrive_backup.domain.model.ScopeArchives;

class ArchiveManagerTextTest {

	private static final DriveScope SCOPE = DriveScope.personal("user@example.com");

	@Test
	void namesEveryArchiveKind() {
		assertEquals("Full", ArchiveManagerText.kind(ArchiveMode.FULL));
		assertEquals("Incremental", ArchiveManagerText.kind(ArchiveMode.INCREMENTAL));
		assertEquals("Merged full", ArchiveManagerText.kind(ArchiveMode.MERGED_FULL));
	}

	@Test
	void describesEveryStateAndFlagsAMissingFile() {
		assertEquals("Current chain (start)", ArchiveManagerText.state(view(ArchiveState.CHAIN_ROOT, false)));
		assertEquals("Current chain", ArchiveManagerText.state(view(ArchiveState.CHAIN_INCREMENTAL, false)));
		assertEquals("Obsolete (merged)", ArchiveManagerText.state(view(ArchiveState.OBSOLETE, false)));
		assertEquals("Earlier chain", ArchiveManagerText.state(view(ArchiveState.PREVIOUS_CHAIN, false)));
		assertEquals("MISSING", ArchiveManagerText.state(view(ArchiveState.CHAIN_INCREMENTAL, true)));
	}

	@Test
	void formatsSizesInReadableUnits() {
		assertEquals("-", ArchiveManagerText.size(null));
		assertEquals("0 B", ArchiveManagerText.size(0L));
		assertEquals("1023 B", ArchiveManagerText.size(1023L));
		assertEquals("1.0 KB", ArchiveManagerText.size(1024L));
		assertEquals("1.5 MB", ArchiveManagerText.size(1_572_864L));
		assertEquals("2.0 GB", ArchiveManagerText.size(2L * 1024 * 1024 * 1024));
	}

	@Test
	void formatsTheCreationTimeInTheGivenZone() {
		assertEquals("2026-09-20 10:30", ArchiveManagerText.created(Instant.parse("2026-09-20T08:30:00Z"),
				ZoneId.of("Europe/Rome")));
	}

	@Test
	void stripsTheKeySuffixSoTheUseCaseDoesNotAddItTwice() {
		assertEquals("Finance", ArchiveManagerText.displayName("Finance (0AIJ4kZ)", "0AIJ4kZ"));
		assertEquals("My Drive", ArchiveManagerText.displayName("My Drive (user@example.com)", "user@example.com"));
		assertEquals("Odd name", ArchiveManagerText.displayName("Odd name", "0AIJ4kZ"));
	}

	@Test
	void theMergeConfirmationCountsTheCurrentChainAndPromisesNothingIsDeleted() {
		ScopeArchives scope = scope(view(ArchiveState.OBSOLETE, false), view(ArchiveState.CHAIN_ROOT, false),
				view(ArchiveState.CHAIN_INCREMENTAL, false), view(ArchiveState.CHAIN_INCREMENTAL, false));

		String text = ArchiveManagerText.mergeConfirmation(scope);

		assertTrue(text.contains("My Drive (user@example.com)"));
		assertTrue(text.contains("3 current archives (the base and 2 incrementals)"));
		assertTrue(text.contains("Nothing is deleted"));
	}

	@Test
	void theMergeConfirmationUsesTheSingularForOneIncremental() {
		ScopeArchives scope = scope(view(ArchiveState.CHAIN_ROOT, false), view(ArchiveState.CHAIN_INCREMENTAL, false));

		assertTrue(ArchiveManagerText.mergeConfirmation(scope).contains("the base and 1 incremental)"));
	}

	@Test
	void describesTheMergeOutcome() {
		Archive archive = new Archive(9L, "user@example.com", DriveScopeType.PERSONAL, 4, null, ArchiveMode.MERGED_FULL,
				RevisionMode.LATEST_ONLY, Instant.now(), "archives/x/archive-0004-merged-full.zip", null, null, false);

		assertTrue(ArchiveManagerText.mergeResult(new MergeResult(archive, false)).contains("archive 4"));
		assertTrue(ArchiveManagerText.mergeResult(new MergeResult(null, true)).contains("cancelled"));
	}

	@Test
	void aVerifiedDeletionSummaryListsTheArchivesCountsAndLostContent() {
		DeletionPlan plan = new DeletionPlan(SCOPE, 3, 4,
				List.of(new ObsoleteArchive(1, 1, "archives/x/archive-0001-full.zip", 2048L),
						new ObsoleteArchive(2, 2, "archives/x/archive-0002-incremental.zip", null)),
				List.of(new LostContent("t", "old report.pdf", "r1", "Trashed file: its content exists only in an obsolete archive")),
				5, 2, 9, List.of());

		String text = ArchiveManagerText.deletionSummary(plan);

		assertTrue(text.contains("Verification passed"));
		assertTrue(text.contains("(2, 2.0 KB)"));
		assertTrue(text.contains("archives/x/archive-0001-full.zip  (2.0 KB)"));
		assertTrue(text.contains("archives/x/archive-0002-incremental.zip  (-)"));
		assertTrue(text.contains("5 content records move"));
		assertTrue(text.contains("2 records of older revisions are removed"));
		assertTrue(text.contains("9 history events are kept"));
		assertTrue(text.contains("old report.pdf - Trashed file"));
		assertTrue(text.contains("captures it again"));
	}

	@Test
	void anUnverifiedDeletionSummaryLeadsWithTheProblemsAndOmitsTheLostSectionWhenThereIsNone() {
		DeletionPlan plan = new DeletionPlan(SCOPE, 3, 4, List.of(), List.of(), 0, 0, 0,
				List.of("Entry a.pdf holds 1 bytes but the manifest says 2"));

		String text = ArchiveManagerText.deletionSummary(plan);

		assertTrue(text.startsWith("Verification FAILED"));
		assertTrue(text.contains("Entry a.pdf holds 1 bytes"));
		assertFalse(text.contains("no longer exist anywhere"));
	}

	@Test
	void describesTheDeletionResultAndAnyFilesLeftBehind() {
		assertEquals("Deleted 3 archive files, freeing 1.0 KB.",
				ArchiveManagerText.deletionResult(new DeletionResult(3, 1024, List.of())));

		String withFailures = ArchiveManagerText.deletionResult(new DeletionResult(1, 10, List.of("a.zip (in use)")));

		assertTrue(withFailures.contains("could not be removed"));
		assertTrue(withFailures.contains("a.zip (in use)"));
	}

	@Test
	void theEarlierChainsSummarySaysTheCurrentChainWasVerifiedAndWhatGoes() {
		DeletionPlan plan = new DeletionPlan(SCOPE, 3, 4,
				List.of(new ObsoleteArchive(1, 1, "archives/x/archive-0001-full.zip", 2048L)),
				List.of(new LostContent("t", "old report.pdf", "r1", "Trashed file: its content exists only in an earlier chain")),
				0, 5, 6, List.of());

		String text = ArchiveManagerText.earlierChainsSummary(plan);

		assertTrue(text.startsWith("Verification passed: every archive of the current chain"));
		assertTrue(text.contains("(1, 2.0 KB)"));
		assertTrue(text.contains("archive-0001-full.zip"));
		assertTrue(text.contains("5 content records of the earlier chains are removed and 6 history events are kept"));
		assertTrue(text.contains("old report.pdf - Trashed file"));
	}

	@Test
	void theEarlierChainsSummaryLeadsWithAFailedVerification() {
		DeletionPlan plan = new DeletionPlan(SCOPE, 3, 4, List.of(), List.of(), 0, 0, 0,
				List.of("Entry a.pdf cannot be read: missing"));

		String text = ArchiveManagerText.earlierChainsSummary(plan);

		assertTrue(text.startsWith("Verification FAILED"));
		assertTrue(text.contains("Entry a.pdf cannot be read"));
	}

	private static ArchiveView view(ArchiveState state, boolean missing) {
		Archive archive = new Archive(1L, "user@example.com", DriveScopeType.PERSONAL, 1, null, ArchiveMode.FULL,
				RevisionMode.LATEST_ONLY, Instant.now(), "archives/x/a.zip", null, null, false);
		return new ArchiveView(archive, state, missing, missing ? null : 10L);
	}

	private static ScopeArchives scope(ArchiveView... views) {
		return new ScopeArchives(SCOPE, "My Drive (user@example.com)", List.of(views), List.of(), true, false, false);
	}
}
