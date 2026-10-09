package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveChainException;
import org.nm.gdrive_backup.domain.model.ArchiveManifest;
import org.nm.gdrive_backup.domain.model.ArchiveManifest.ManifestFile;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.BackupStopMode;
import org.nm.gdrive_backup.domain.model.DeletionCommit;
import org.nm.gdrive_backup.domain.model.DeletionPlan;
import org.nm.gdrive_backup.domain.model.DeletionResult;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.DriveScopeType;
import org.nm.gdrive_backup.domain.model.FileCapture;
import org.nm.gdrive_backup.domain.model.RevisionMode;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.port.out.ArchiveDeletionCommitPort;
import org.nm.gdrive_backup.domain.port.out.ArchivePort;
import org.nm.gdrive_backup.domain.port.out.ArchiveStoragePort;
import org.nm.gdrive_backup.domain.port.out.FileCapturePort;
import org.nm.gdrive_backup.domain.port.out.FileEventPort;
import org.nm.gdrive_backup.domain.port.out.FileMetadataPort;

class ArchiveDeletionServiceTest {

	private static final DriveScope SCOPE = DriveScope.personal("user@example.com");
	private static final String PDF = "application/pdf";
	private static final String P1 = "archives/My Drive (user@example.com)/archive-0001-full.zip";
	private static final String P2 = "archives/My Drive (user@example.com)/archive-0002-incremental.zip";
	private static final String P3 = "archives/My Drive (user@example.com)/archive-0003-merged-full.zip";
	private static final String P4 = "archives/My Drive (user@example.com)/archive-0004-incremental.zip";

	private final ArchivePort archivePort = mock(ArchivePort.class);
	private final FakeArchiveReaderPort readers = new FakeArchiveReaderPort();
	private final ArchiveStoragePort storagePort = mock(ArchiveStoragePort.class);
	private final FileCapturePort capturePort = mock(FileCapturePort.class);
	private final FileEventPort eventPort = mock(FileEventPort.class);
	private final FileMetadataPort metadataPort = mock(FileMetadataPort.class);
	private final ArchiveDeletionCommitPort commitPort = mock(ArchiveDeletionCommitPort.class);
	private final BackupActivity activity = new BackupActivity();
	private final BackupProgressTracker progressTracker = mock(BackupProgressTracker.class);
	private final BackupCancellation cancellation = new BackupCancellation();
	private final ArchiveDeletionService service = new ArchiveDeletionService(archivePort, readers, storagePort,
			capturePort, eventPort, metadataPort, commitPort, activity, progressTracker, cancellation);

	private Archive full;
	private Archive incremental;
	private Archive merged;
	private Archive next;

	@BeforeEach
	void buildAMergedChain() {
		full = row(1, null, ArchiveMode.FULL, P1);
		incremental = row(2, 1L, ArchiveMode.INCREMENTAL, P2);
		merged = row(3, null, ArchiveMode.MERGED_FULL, P3);
		next = row(4, 3L, ArchiveMode.INCREMENTAL, P4);
		when(archivePort.findByScopeKey("user@example.com")).thenReturn(List.of(full, incremental, merged, next));
		when(archivePort.findSourceArchiveIds(anyLong())).thenReturn(List.of());
		when(archivePort.findSourceArchiveIds(3L)).thenReturn(List.of(1L, 2L));
		when(storagePort.sizeOf(P1)).thenReturn(OptionalLong.of(100));
		when(storagePort.sizeOf(P2)).thenReturn(OptionalLong.of(50));

		// merged full carries: a (rev r2), b (rev r1), a trashed file t without content, and a folder
		readers.add(P3, mergedManifest(
				new ManifestFile("folder-1", false, "Docs", List.of(), null, "application/vnd.google-apps.folder", false,
						null, null, null, null),
				new ManifestFile("a", false, "a.pdf", List.of("folder-1"), null, PDF, false, "r2", "Docs/a.pdf", 3L, null),
				new ManifestFile("b", false, "b.pdf", List.of(), null, PDF, false, "r1", "b.pdf", 2L, null),
				new ManifestFile("t", false, "t.pdf", List.of(), null, PDF, true, null, null, null, null)),
				Map.of("Docs/a.pdf", "A22", "b.pdf", "B1"));

		// the index: archive 1 holds a r1, b r1, t r1, gone r1; archive 2 holds a r2
		when(capturePort.findByArchiveId(1L)).thenReturn(List.of(
				capture(10, "a", "r1", 1), capture(11, "b", "r1", 1), capture(12, "t", "r1", 1),
				capture(13, "gone", "r1", 1)));
		when(capturePort.findByArchiveId(2L)).thenReturn(List.of(capture(14, "a", "r2", 2)));
		when(eventPort.countByArchiveId(1L)).thenReturn(4);
		when(eventPort.countByArchiveId(2L)).thenReturn(2);
		when(metadataPort.findByFileId("a")).thenReturn(Optional.of(file("a", "a.pdf", false, 14L)));
		when(metadataPort.findByFileId("b")).thenReturn(Optional.of(file("b", "b.pdf", false, 11L)));
		when(metadataPort.findByFileId("t")).thenReturn(Optional.of(file("t", "t.pdf", true, 12L)));
		when(metadataPort.findByFileId("gone")).thenReturn(Optional.of(file("gone", "gone.pdf", false, 13L)));
	}

	@Test
	void aVerifiedPlanListsTheObsoleteArchivesTheIndexChangesAndTheContentThatWillBeLost() throws Exception {
		DeletionPlan plan = service.prepare(SCOPE, null).orElseThrow();

		assertTrue(plan.verified(), plan.verificationProblems().toString());
		assertEquals(3L, plan.mergedArchiveId());
		assertEquals(4L, plan.tipArchiveId());
		assertEquals(List.of(1, 2), plan.obsolete().stream().map(a -> a.sequenceNumber()).toList());
		assertEquals(150L, plan.totalBytes());
		assertEquals(2, plan.capturesToRepoint());
		assertEquals(3, plan.capturesToRemove());
		assertEquals(6, plan.eventsToRepoint());
		assertEquals(List.of("t", "gone"), plan.lostContent().stream().map(l -> l.fileId()).toList());
		assertTrue(plan.lostContent().get(0).reason().startsWith("Trashed file"));
		assertTrue(plan.lostContent().get(1).reason().startsWith("Deleted from Drive"));
		verify(commitPort, never()).apply(any());
		verify(storagePort, never()).delete(any());
	}

	@Test
	void preparingReportsProgressPerVerifiedEntryAndDoesNotChangeAnything() throws Exception {
		service.prepare(SCOPE, "My Drive");

		verify(progressTracker).enumerated(2);
		verify(progressTracker).itemProcessed("a.pdf");
		verify(progressTracker).itemProcessed("b.pdf");
		verify(progressTracker).jobFinished();
		verify(storagePort, never()).delete(any());
	}

	@Test
	void aSizeThatDisagreesWithTheManifestIsAVerificationProblem() {
		readers.add(P3, mergedManifest(
				new ManifestFile("a", false, "a.pdf", List.of(), null, PDF, false, "r2", "Docs/a.pdf", 99L, null)),
				Map.of("Docs/a.pdf", "A22"));

		DeletionPlan plan = service.prepare(SCOPE, null).orElseThrow();

		assertFalse(plan.verified());
		assertTrue(plan.verificationProblems().getFirst().contains("Docs/a.pdf"));
	}

	@Test
	void anEntryMissingFromTheMergedArchiveIsAVerificationProblem() {
		readers.add(P3, mergedManifest(
				new ManifestFile("a", false, "a.pdf", List.of(), null, PDF, false, "r2", "Docs/a.pdf", 3L, null)),
				Map.of());

		DeletionPlan plan = service.prepare(SCOPE, null).orElseThrow();

		assertFalse(plan.verified());
		assertTrue(plan.verificationProblems().getFirst().contains("cannot be read"));
	}

	@Test
	void aMergedArchiveThatCannotBeOpenedIsAVerificationProblemNotACrash() {
		Archive missing = row(3, null, ArchiveMode.MERGED_FULL, "archives/gone.zip");
		when(archivePort.findByScopeKey("user@example.com")).thenReturn(List.of(full, incremental, missing, next));

		DeletionPlan plan = service.prepare(SCOPE, null).orElseThrow();

		assertFalse(plan.verified());
		assertEquals(0, plan.capturesToRepoint());
	}

	@Test
	void aManifestThatContradictsItsRecordIsAVerificationProblem() {
		readers.add(P3, new ArchiveManifest(SCOPE, ArchiveMode.MERGED_FULL, RevisionMode.LATEST_ONLY, 9, null,
				Instant.now(), null, null, List.of(), List.of(), List.of()), Map.of());

		DeletionPlan plan = service.prepare(SCOPE, null).orElseThrow();

		assertFalse(plan.verified());
		assertTrue(plan.verificationProblems().getFirst().contains("number 9"));
	}

	@Test
	void aLiveFileWhoseCurrentRevisionIsNotWhatTheMergedArchiveCarriesIsAProblem() {
		when(metadataPort.findByFileId("a")).thenReturn(Optional.of(file("a", "a.pdf", false, 10L)));

		DeletionPlan plan = service.prepare(SCOPE, null).orElseThrow();

		assertFalse(plan.verified());
		assertTrue(plan.verificationProblems().getFirst().contains("a.pdf"));
	}

	@Test
	void anImmediateStopWhileVerifyingCancelsThePlan() {
		org.mockito.Mockito.doAnswer(invocation -> {
			cancellation.requestStop(BackupStopMode.IMMEDIATE);
			return null;
		}).when(progressTracker).itemProcessed("a.pdf");

		Optional<DeletionPlan> plan = service.prepare(SCOPE, null);

		assertTrue(plan.isEmpty());
		verify(commitPort, never()).apply(any());
	}

	@Test
	void nothingToDeleteWhenTheChainDoesNotStartFromAMergedFull() {
		when(archivePort.findByScopeKey("user@example.com")).thenReturn(List.of(full, incremental));

		assertThrows(IllegalStateException.class, () -> service.prepare(SCOPE, null));
	}

	@Test
	void nothingToDeleteWhenNoMergeLeftObsoleteArchives() {
		when(archivePort.findSourceArchiveIds(3L)).thenReturn(List.of());

		assertThrows(IllegalStateException.class, () -> service.prepare(SCOPE, null));
	}

	@Test
	void aBrokenChainIsReportedAsAChainProblem() {
		Archive orphan = row(4, 99L, ArchiveMode.INCREMENTAL, P4);
		when(archivePort.findByScopeKey("user@example.com")).thenReturn(List.of(full, incremental, merged, orphan));

		assertThrows(ArchiveChainException.class, () -> service.prepare(SCOPE, null));
	}

	@Test
	void executingUpdatesTheDatabaseFirstThenRemovesTheFilesNewestFirst() throws Exception {
		DeletionPlan plan = service.prepare(SCOPE, null).orElseThrow();

		DeletionResult result = service.execute(SCOPE, plan);

		ArgumentCaptor<DeletionCommit> commit = ArgumentCaptor.forClass(DeletionCommit.class);
		var order = inOrder(commitPort, storagePort);
		order.verify(commitPort).apply(commit.capture());
		order.verify(storagePort).delete(P2);
		order.verify(storagePort).delete(P1);
		assertEquals(3L, commit.getValue().mergedArchiveId());
		assertEquals(List.of(2L, 1L), commit.getValue().obsoleteArchiveIds());
		assertEquals(Map.of(11L, "b.pdf", 14L, "Docs/a.pdf"), commit.getValue().captureIdToNewEntry());
		assertEquals(List.of(10L, 12L, 13L), commit.getValue().captureIdsToRemove());
		assertEquals(2, result.deletedFiles());
		assertEquals(150L, result.freedBytes());
		assertTrue(result.filesThatCouldNotBeDeleted().isEmpty());
	}

	@Test
	void aFileThatCannotBeDeletedIsReportedWithoutFailingTheRest() throws Exception {
		DeletionPlan plan = service.prepare(SCOPE, null).orElseThrow();
		doThrow(new IOException("in use")).when(storagePort).delete(P2);

		DeletionResult result = service.execute(SCOPE, plan);

		assertEquals(1, result.deletedFiles());
		assertEquals(100L, result.freedBytes());
		assertEquals(1, result.filesThatCouldNotBeDeleted().size());
		assertTrue(result.filesThatCouldNotBeDeleted().getFirst().contains(P2));
		verify(storagePort).delete(P1);
	}

	@Test
	void noFileIsTouchedWhenTheDatabaseUpdateFails() throws Exception {
		DeletionPlan plan = service.prepare(SCOPE, null).orElseThrow();
		doThrow(new IllegalStateException("db locked")).when(commitPort).apply(any());

		assertThrows(IllegalStateException.class, () -> service.execute(SCOPE, plan));

		verify(storagePort, never()).delete(any());
	}

	@Test
	void anUnverifiedPlanIsRefused() {
		readers.add(P3, mergedManifest(
				new ManifestFile("a", false, "a.pdf", List.of(), null, PDF, false, "r2", "Docs/a.pdf", 99L, null)),
				Map.of("Docs/a.pdf", "A22"));
		DeletionPlan plan = service.prepare(SCOPE, null).orElseThrow();

		assertThrows(IllegalStateException.class, () -> service.execute(SCOPE, plan));

		verify(commitPort, never()).apply(any());
	}

	@Test
	void aPlanForAnOlderChainIsRefusedAsStale() throws Exception {
		DeletionPlan plan = service.prepare(SCOPE, null).orElseThrow();
		Archive laterIncremental = row(5, 4L, ArchiveMode.INCREMENTAL, "archives/x/archive-0005-incremental.zip");
		when(archivePort.findByScopeKey("user@example.com"))
				.thenReturn(List.of(full, incremental, merged, next, laterIncremental));

		IllegalStateException exception = assertThrows(IllegalStateException.class, () -> service.execute(SCOPE, plan));

		assertTrue(exception.getMessage().contains("review it again"));
		verify(commitPort, never()).apply(any());
	}

	@Test
	void aPlanWhoseIndexChangedSincePreparingIsRefusedAsStale() {
		DeletionPlan plan = service.prepare(SCOPE, null).orElseThrow();
		when(eventPort.countByArchiveId(1L)).thenReturn(5);

		assertThrows(IllegalStateException.class, () -> service.execute(SCOPE, plan));

		verify(commitPort, never()).apply(any());
	}

	@Test
	void executingRefusesToRunBesideABackup() {
		DeletionPlan plan = service.prepare(SCOPE, null).orElseThrow();

		assertThrows(IllegalStateException.class, () -> activity.duringBackup(() -> service.execute(SCOPE, plan)));
	}

	/** Archives 1 and 2 are a chain that a from-scratch full (3) and its incremental (4) replaced. */
	private void buildANewChainAfterAnEarlierOne() {
		Archive newFull = row(3, null, ArchiveMode.FULL, P3);
		when(archivePort.findByScopeKey("user@example.com")).thenReturn(List.of(full, incremental, newFull, next));
		when(archivePort.findSourceArchiveIds(3L)).thenReturn(List.of());
		readers.add(P3, manifest(ArchiveMode.FULL, 3,
				new ManifestFile("a", false, "a.pdf", List.of(), null, PDF, false, "r2", "a.pdf", 3L, null),
				new ManifestFile("t", false, "t.pdf", List.of(), null, PDF, true, null, null, null, null)),
				Map.of("a.pdf", "A22"));
		readers.add(P4, manifest(ArchiveMode.INCREMENTAL, 4,
				new ManifestFile("b", false, "b.pdf", List.of(), null, PDF, false, "r2", "content/b", 2L, null)),
				Map.of("content/b", "B2"));
		// the new chain captured a and b again; t (trashed) and gone are still known only from the earlier chain
		when(metadataPort.findByFileId("a")).thenReturn(Optional.of(file("a", "a.pdf", false, 20L)));
		when(metadataPort.findByFileId("b")).thenReturn(Optional.of(file("b", "b.pdf", false, 21L)));
	}

	@Test
	void anEarlierChainsPlanVerifiesTheWholeCurrentChainAndListsWhatGoes() throws Exception {
		buildANewChainAfterAnEarlierOne();

		DeletionPlan plan = service.prepareEarlierChains(SCOPE, null).orElseThrow();

		assertTrue(plan.verified(), plan.verificationProblems().toString());
		assertEquals(3L, plan.mergedArchiveId());
		assertEquals(4L, plan.tipArchiveId());
		assertEquals(List.of(1, 2), plan.obsolete().stream().map(a -> a.sequenceNumber()).toList());
		assertEquals(150L, plan.totalBytes());
		assertEquals(0, plan.capturesToRepoint());
		assertEquals(5, plan.capturesToRemove());
		assertEquals(6, plan.eventsToRepoint());
		assertEquals(List.of("t", "gone"), plan.lostContent().stream().map(l -> l.fileId()).toList());
		assertTrue(plan.lostContent().get(0).reason().startsWith("Trashed file"));
		assertTrue(plan.lostContent().get(1).reason().startsWith("Not captured by the current chain"));
		verify(progressTracker).enumerated(2);
		verify(progressTracker).itemProcessed("a.pdf");
		verify(progressTracker).itemProcessed("b.pdf");
		verify(commitPort, never()).apply(any());
		verify(storagePort, never()).delete(any());
	}

	@Test
	void aDamagedArchiveAnywhereInTheCurrentChainBlocksDeletingEarlierChains() {
		buildANewChainAfterAnEarlierOne();
		readers.add(P4, manifest(ArchiveMode.INCREMENTAL, 4,
				new ManifestFile("b", false, "b.pdf", List.of(), null, PDF, false, "r2", "content/b", 2L, null)),
				Map.of());

		DeletionPlan plan = service.prepareEarlierChains(SCOPE, null).orElseThrow();

		assertFalse(plan.verified());
		assertTrue(plan.verificationProblems().getFirst().contains("content/b"));
		assertThrows(IllegalStateException.class, () -> service.executeEarlierChains(SCOPE, plan));
		verify(commitPort, never()).apply(any());
	}

	@Test
	void executingAnEarlierChainsPlanRemovesItsIndexRowsThenItsFilesNewestFirst() throws Exception {
		buildANewChainAfterAnEarlierOne();
		DeletionPlan plan = service.prepareEarlierChains(SCOPE, null).orElseThrow();

		DeletionResult result = service.executeEarlierChains(SCOPE, plan);

		ArgumentCaptor<DeletionCommit> commit = ArgumentCaptor.forClass(DeletionCommit.class);
		var order = inOrder(commitPort, storagePort);
		order.verify(commitPort).apply(commit.capture());
		order.verify(storagePort).delete(P2);
		order.verify(storagePort).delete(P1);
		assertEquals(3L, commit.getValue().mergedArchiveId());
		assertEquals(List.of(2L, 1L), commit.getValue().obsoleteArchiveIds());
		assertTrue(commit.getValue().captureIdToNewEntry().isEmpty());
		assertEquals(List.of(10L, 11L, 12L, 13L, 14L), commit.getValue().captureIdsToRemove());
		assertEquals(2, result.deletedFiles());
		assertEquals(150L, result.freedBytes());
	}

	@Test
	void anEarlierChainsPlanIsRefusedAsStaleOnceTheChainMovedOn() {
		buildANewChainAfterAnEarlierOne();
		DeletionPlan plan = service.prepareEarlierChains(SCOPE, null).orElseThrow();
		Archive newFull = row(3, null, ArchiveMode.FULL, P3);
		Archive later = row(5, 4L, ArchiveMode.INCREMENTAL, "archives/x/archive-0005-incremental.zip");
		when(archivePort.findByScopeKey("user@example.com"))
				.thenReturn(List.of(full, incremental, newFull, next, later));

		IllegalStateException exception = assertThrows(IllegalStateException.class,
				() -> service.executeEarlierChains(SCOPE, plan));

		assertTrue(exception.getMessage().contains("review it again"));
		verify(commitPort, never()).apply(any());
	}

	@Test
	void obsoleteArchivesOfTheCurrentChainAreNotAnEarlierChain() {
		assertThrows(IllegalStateException.class, () -> service.prepareEarlierChains(SCOPE, null));
	}

	private ArchiveManifest manifest(ArchiveMode mode, int sequenceNumber, ManifestFile... files) {
		return new ArchiveManifest(SCOPE, mode, RevisionMode.LATEST_ONLY, sequenceNumber, null, Instant.now(), null,
				"t" + sequenceNumber, List.of(), List.of(files), List.of());
	}

	private ArchiveManifest mergedManifest(ManifestFile... files) {
		return new ArchiveManifest(SCOPE, ArchiveMode.MERGED_FULL, RevisionMode.LATEST_ONLY, 3, null, Instant.now(), null,
				"t3", List.of(), List.of(files), List.of());
	}

	private static Archive row(int sequenceNumber, Long baseId, ArchiveMode mode, String path) {
		return new Archive((long) sequenceNumber, "user@example.com", DriveScopeType.PERSONAL, sequenceNumber, baseId,
				mode, RevisionMode.LATEST_ONLY, Instant.now(), path, null, null, false);
	}

	private static FileCapture capture(long id, String fileId, String revision, long archiveId) {
		return new FileCapture(id, fileId, revision, Instant.now(), archiveId, "entry-" + id, 1);
	}

	private static StoredFile file(String id, String name, boolean trashed, Long currentVersionId) {
		return new StoredFile(id, "user@example.com", name, "", null, PDF, trashed, "r", currentVersionId);
	}
}
