package org.nm.gdrive_backup.adapter.in.javafx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.ArchiveState;
import org.nm.gdrive_backup.domain.model.ArchiveView;
import org.nm.gdrive_backup.domain.model.AvailableDrive;
import org.nm.gdrive_backup.domain.model.BackupMode;
import org.nm.gdrive_backup.domain.model.BackupPhase;
import org.nm.gdrive_backup.domain.model.BackupProgress;
import org.nm.gdrive_backup.domain.model.BackupResult;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.DriveScopeType;
import org.nm.gdrive_backup.domain.model.RevisionMode;
import org.nm.gdrive_backup.domain.model.ScopeArchives;

class BackupDriveTextTest {

	private static final ZoneId ZONE = ZoneId.of("Europe/Rome");
	private static final AvailableDrive PERSONAL = new AvailableDrive("root", "My Drive", false);
	private static final AvailableDrive SHARED = new AvailableDrive("drive-1", "Finance", true);

	@Test
	void theScopeKeyMatchesHowDriveBackupServiceBuildsItsScope() {
		assertEquals("user@example.com", BackupDriveText.scopeKey(PERSONAL, "user@example.com"));
		assertEquals("drive-1", BackupDriveText.scopeKey(SHARED, "user@example.com"));
	}

	@Test
	void theSubtitleNamesTheOwnerOrSaysSharedDrive() {
		assertEquals("user@example.com", BackupDriveText.subtitle(PERSONAL, "user@example.com"));
		assertEquals("Shared drive", BackupDriveText.subtitle(SHARED, "user@example.com"));
	}

	@Test
	void modeWordingCoversBothModes() {
		assertEquals("Incremental", BackupDriveText.modeLabel(BackupMode.INCREMENTAL));
		assertEquals("Full", BackupDriveText.modeLabel(BackupMode.FULL));
		assertTrue(BackupDriveText.modeDescription(BackupMode.INCREMENTAL).contains("Only changes"));
		assertTrue(BackupDriveText.modeDescription(BackupMode.FULL).contains("Re-downloads"));
	}

	@Test
	void selectionCountReportsNoneLoadedOrAFraction() {
		assertEquals("No drives loaded", BackupDriveText.selectionCount(0, 0));
		assertEquals("0 of 1 drive selected", BackupDriveText.selectionCount(0, 1));
		assertEquals("2 of 4 drives selected", BackupDriveText.selectionCount(2, 4));
	}

	@Test
	void aDriveWithNoArchivesIsNew() {
		BackupDriveText.DriveStatus status = BackupDriveText.idleStatus(null, ZONE);

		assertEquals("New", status.label());
		assertEquals("Never backed up", status.detail());
		assertEquals(BackupDriveText.Tone.NEUTRAL, status.tone());
	}

	@Test
	void aHealthyChainIsOkAndNamesItsLastArchive() {
		ScopeArchives catalog = scope(List.of(), archiveView(1, ArchiveMode.FULL), archiveView(2, ArchiveMode.INCREMENTAL));

		BackupDriveText.DriveStatus status = BackupDriveText.idleStatus(catalog, ZONE);

		assertEquals("OK", status.label());
		assertTrue(status.detail().startsWith("Last archive: "));
		assertTrue(status.detail().contains("incremental"));
		assertEquals(BackupDriveText.Tone.OK, status.tone());
	}

	@Test
	void aChainWithWarningsIsFlaggedAsAProblem() {
		ScopeArchives catalog = scope(List.of("Archive 1 (a.zip) is missing"), archiveView(1, ArchiveMode.FULL));

		BackupDriveText.DriveStatus status = BackupDriveText.idleStatus(catalog, ZONE);

		assertEquals("Chain problem", status.label());
		assertEquals(BackupDriveText.Tone.WARN, status.tone());
	}

	@Test
	void queuedStatusIsNeutral() {
		BackupDriveText.DriveStatus status = BackupDriveText.queuedStatus();

		assertEquals("Queued", status.label());
		assertEquals(BackupDriveText.Tone.NEUTRAL, status.tone());
	}

	@Test
	void aDriveAlreadyCountedAsCompletedShowsFinishedEvenIfAnotherIsNowRunning() {
		BackupProgress progress = progress(2, 2, BackupPhase.BACKING_UP, 5, 10);

		BackupDriveText.DriveStatus status = BackupDriveText.liveStatus(0, progress);

		assertEquals("Finished", status.label());
		assertEquals(BackupDriveText.Tone.OK, status.tone());
	}

	@Test
	void theActiveDriveShowsProgressWithAKnownTotal() {
		BackupProgress progress = progress(1, 0, BackupPhase.BACKING_UP, 312, 505);

		BackupDriveText.DriveStatus status = BackupDriveText.liveStatus(0, progress);

		assertEquals("Running", status.label());
		assertEquals("312 / 505 files", status.detail());
		assertEquals(BackupDriveText.Tone.INFO, status.tone());
	}

	@Test
	void theActiveDriveShowsItemsProcessedWhenTheTotalIsUnknown() {
		BackupProgress progress = progress(1, 0, BackupPhase.BACKING_UP, 48, null);

		assertEquals("48 items processed", BackupDriveText.liveStatus(0, progress).detail());
	}

	@Test
	void theActiveDrivePhaseIsShownWhileEnumeratingOrPackaging() {
		assertEquals("Enumerating...", BackupDriveText.liveStatus(0, progress(1, 0, BackupPhase.ENUMERATING, 0, null)).detail());
		assertEquals("Packaging...", BackupDriveText.liveStatus(0, progress(1, 0, BackupPhase.PACKAGING, 5, 5)).detail());
	}

	@Test
	void aDriveNotYetReachedIsQueued() {
		BackupProgress progress = progress(1, 0, BackupPhase.BACKING_UP, 5, 10);

		assertEquals("Queued", BackupDriveText.liveStatus(1, progress).label());
	}

	@Test
	void withNoResultTheDriveNeverStarted() {
		assertEquals("Not started", BackupDriveText.resultStatus(null).label());
	}

	@Test
	void aFailedResultCarriesItsReasonAsTheDetail() {
		BackupDriveText.DriveStatus status = BackupDriveText.resultStatus(
				BackupResult.failed(DriveScope.sharedDrive("drive-1"), "No access"));

		assertEquals("Failed", status.label());
		assertEquals("No access", status.detail());
		assertEquals(BackupDriveText.Tone.ERROR, status.tone());
	}

	@Test
	void aCancelledResultIsDistinctFromADoneOne() {
		BackupResult cancelled = new BackupResult(DriveScope.personal("user@example.com"), 4, true, true);
		BackupResult done = new BackupResult(DriveScope.personal("user@example.com"), 4, true, false);

		assertEquals("Cancelled", BackupDriveText.resultStatus(cancelled).label());
		assertEquals(BackupDriveText.Tone.WARN, BackupDriveText.resultStatus(cancelled).tone());
		assertEquals("Done", BackupDriveText.resultStatus(done).label());
		assertEquals(BackupDriveText.Tone.OK, BackupDriveText.resultStatus(done).tone());
	}

	private static BackupProgress progress(int driveNumber, int completedDrives, BackupPhase phase,
			int processedItems, Integer totalItems) {
		Instant now = Instant.now();
		return new BackupProgress("Drive", false, driveNumber, 2, completedDrives, phase, null, processedItems,
				totalItems, now, now, null, null);
	}

	private static ArchiveView archiveView(int sequenceNumber, ArchiveMode mode) {
		Archive archive = new Archive((long) sequenceNumber, "user@example.com", DriveScopeType.PERSONAL,
				sequenceNumber, null, mode, RevisionMode.LATEST_ONLY, Instant.parse("2026-09-25T18:40:00Z"),
				"archives/x/a.zip", null, null, false);
		return new ArchiveView(archive, ArchiveState.CHAIN_INCREMENTAL, false, 10L);
	}

	private static ScopeArchives scope(List<String> warnings, ArchiveView... views) {
		return new ScopeArchives(DriveScope.personal("user@example.com"), "My Drive (user@example.com)",
				List.of(views), warnings, true, false);
	}
}
