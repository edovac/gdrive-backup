package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.RevisionMode;
import org.nm.gdrive_backup.domain.port.out.ArchivePort;

class ArchiveRunPlannerTest {

	private static final DriveScope SCOPE = DriveScope.personal("user@example.com");

	private final ArchivePort archivePort = mock(ArchivePort.class);
	private final ArchiveRunPlanner planner = new ArchiveRunPlanner(archivePort);

	@Test
	void theFirstFullArchiveOnAScopeIsSequenceOneWithNoBase() {
		when(archivePort.findByScopeKey("user@example.com")).thenReturn(List.of());

		ArchiveRunPlanner.Plan plan = planner.planFull(SCOPE, null);

		assertEquals(1, plan.sequenceNumber());
		assertNull(plan.baseArchiveId());
		assertEquals("archives/My Drive (user@example.com)/archive-0001-full.zip", plan.relativeTargetPath());
	}

	@Test
	void aLaterFullRunCountsPastTheScopesHighestSequenceNumber() {
		when(archivePort.findByScopeKey("user@example.com")).thenReturn(List.of(archive(1, 10L), archive(3, 30L)));

		ArchiveRunPlanner.Plan plan = planner.planFull(SCOPE, null);

		assertEquals(4, plan.sequenceNumber());
		assertNull(plan.baseArchiveId());
	}

	@Test
	void anIncrementalArchiveChainsToTheLatestArchive() {
		when(archivePort.findByScopeKey("user@example.com")).thenReturn(List.of(archive(1, 10L), archive(2, 20L)));

		ArchiveRunPlanner.Plan plan = planner.planIncremental(SCOPE, null);

		assertEquals(3, plan.sequenceNumber());
		assertEquals(20L, plan.baseArchiveId());
		assertEquals("archives/My Drive (user@example.com)/archive-0003-incremental.zip", plan.relativeTargetPath());
	}

	@Test
	void anIncrementalArchiveNeedsSomethingToChainFrom() {
		when(archivePort.findByScopeKey("user@example.com")).thenReturn(List.of());

		assertThrows(IllegalStateException.class, () -> planner.planIncremental(SCOPE, null));
	}

	@Test
	void namesASharedDriveFolderFromItsDisplayName() {
		DriveScope shared = DriveScope.sharedDrive("drive-1");
		when(archivePort.findByScopeKey("drive-1")).thenReturn(List.of());

		assertEquals("archives/Finance (drive-1)/archive-0001-full.zip",
				planner.planFull(shared, "Finance").relativeTargetPath());
	}

	@Test
	void buildsTheArchiveRowFromThePlan() {
		when(archivePort.findByScopeKey("user@example.com")).thenReturn(List.of(archive(1, 10L)));
		Instant now = Instant.parse("2026-09-19T10:00:00Z");

		Archive archive = planner.planIncremental(SCOPE, null).toArchive(now, "from", "to");

		assertEquals(new Archive(null, "user@example.com", 2, 10L, ArchiveMode.INCREMENTAL, RevisionMode.LATEST_ONLY,
				now, "archives/My Drive (user@example.com)/archive-0002-incremental.zip", "from", "to", false), archive);
	}

	private static Archive archive(int sequenceNumber, long id) {
		return new Archive(id, "user@example.com", sequenceNumber, null, ArchiveMode.FULL, RevisionMode.LATEST_ONLY,
				Instant.now(), "archives/x.zip", null, null, false);
	}
}
