package org.nm.gdrive_backup.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.ArchiveState;
import org.nm.gdrive_backup.domain.model.ArchiveView;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.DriveScopeType;
import org.nm.gdrive_backup.domain.model.RevisionMode;
import org.nm.gdrive_backup.domain.model.ScopeArchives;
import org.nm.gdrive_backup.domain.port.out.ArchivePort;
import org.nm.gdrive_backup.domain.port.out.ArchiveStoragePort;

class ArchiveCatalogServiceTest {

	private final ArchivePort archivePort = mock(ArchivePort.class);
	private final ArchiveStoragePort storagePort = mock(ArchiveStoragePort.class);
	private final ArchiveCatalogService service = new ArchiveCatalogService(archivePort, storagePort);
	private final List<Archive> rows = new ArrayList<>();

	@BeforeEach
	void everyFileExistsByDefault() {
		when(storagePort.sizeOf(anyString())).thenReturn(OptionalLong.of(1000));
		when(archivePort.findAll()).thenAnswer(invocation -> List.copyOf(rows));
		when(archivePort.findSourceArchiveIds(anyLong())).thenReturn(List.of());
	}

	@Test
	void aLinearChainHasARootAndIncrementalsAndCanBeMerged() {
		add(1, null, ArchiveMode.FULL, "user@example.com");
		add(2, 1L, ArchiveMode.INCREMENTAL, "user@example.com");
		add(3, 2L, ArchiveMode.INCREMENTAL, "user@example.com");

		ScopeArchives scope = only();

		assertEquals(List.of(ArchiveState.CHAIN_ROOT, ArchiveState.CHAIN_INCREMENTAL, ArchiveState.CHAIN_INCREMENTAL),
				states(scope));
		assertEquals("My Drive (user@example.com)", scope.label());
		assertEquals(DriveScope.personal("user@example.com"), scope.scope());
		assertEquals(1000L, scope.archives().getFirst().sizeBytes());
		assertTrue(scope.canMerge());
		assertFalse(scope.hasObsolete());
		assertTrue(scope.warnings().isEmpty());
	}

	@Test
	void afterAMergeTheConsumedArchivesAreObsoleteAndTheMergedFullIsTheRoot() {
		add(1, null, ArchiveMode.FULL, "user@example.com");
		add(2, 1L, ArchiveMode.INCREMENTAL, "user@example.com");
		add(3, null, ArchiveMode.MERGED_FULL, "user@example.com");
		add(4, 3L, ArchiveMode.INCREMENTAL, "user@example.com");
		when(archivePort.findSourceArchiveIds(3L)).thenReturn(List.of(1L, 2L));

		ScopeArchives scope = only();

		assertEquals(List.of(ArchiveState.OBSOLETE, ArchiveState.OBSOLETE, ArchiveState.CHAIN_ROOT,
				ArchiveState.CHAIN_INCREMENTAL), states(scope));
		assertTrue(scope.hasObsolete());
		assertTrue(scope.canMerge());
	}

	@Test
	void aSecondMergeMakesTheFirstMergedFullAndItsSourcesObsoleteToo() {
		add(1, null, ArchiveMode.FULL, "user@example.com");
		add(2, 1L, ArchiveMode.INCREMENTAL, "user@example.com");
		add(3, null, ArchiveMode.MERGED_FULL, "user@example.com");
		add(4, 3L, ArchiveMode.INCREMENTAL, "user@example.com");
		add(5, null, ArchiveMode.MERGED_FULL, "user@example.com");
		when(archivePort.findSourceArchiveIds(3L)).thenReturn(List.of(1L, 2L));
		when(archivePort.findSourceArchiveIds(5L)).thenReturn(List.of(3L, 4L));

		ScopeArchives scope = only();

		assertEquals(List.of(ArchiveState.OBSOLETE, ArchiveState.OBSOLETE, ArchiveState.OBSOLETE,
				ArchiveState.OBSOLETE, ArchiveState.CHAIN_ROOT), states(scope));
		assertFalse(scope.canMerge(), "a lone root has nothing to merge");
	}

	@Test
	void aFreshFullLeavesTheOlderChainBehindAsAPreviousChainNotObsolete() {
		add(1, null, ArchiveMode.FULL, "user@example.com");
		add(2, 1L, ArchiveMode.INCREMENTAL, "user@example.com");
		add(3, null, ArchiveMode.FULL, "user@example.com");

		ScopeArchives scope = only();

		assertEquals(List.of(ArchiveState.PREVIOUS_CHAIN, ArchiveState.PREVIOUS_CHAIN, ArchiveState.CHAIN_ROOT),
				states(scope));
		assertFalse(scope.hasObsolete());
		assertFalse(scope.canMerge());
	}

	@Test
	void aMissingFileInTheCurrentChainIsAWarningAndBlocksMerging() {
		add(1, null, ArchiveMode.FULL, "user@example.com");
		add(2, 1L, ArchiveMode.INCREMENTAL, "user@example.com");
		when(storagePort.sizeOf(path(2))).thenReturn(OptionalLong.empty());

		ScopeArchives scope = only();

		ArchiveView missing = scope.archives().get(1);
		assertTrue(missing.fileMissing());
		assertNull(missing.sizeBytes());
		assertEquals(1, scope.warnings().size());
		assertTrue(scope.warnings().getFirst().contains("Archive 2"));
		assertFalse(scope.canMerge());
	}

	@Test
	void aMissingFileOutsideTheCurrentChainIsShownButIsNotAWarning() {
		add(1, null, ArchiveMode.FULL, "user@example.com");
		add(2, null, ArchiveMode.MERGED_FULL, "user@example.com");
		add(3, 2L, ArchiveMode.INCREMENTAL, "user@example.com");
		when(archivePort.findSourceArchiveIds(2L)).thenReturn(List.of(1L));
		when(storagePort.sizeOf(path(1))).thenReturn(OptionalLong.empty());

		ScopeArchives scope = only();

		assertTrue(scope.archives().getFirst().fileMissing());
		assertTrue(scope.warnings().isEmpty());
		assertTrue(scope.canMerge());
	}

	@Test
	void aBrokenBaseLinkIsAWarningAndBlocksMerging() {
		add(1, null, ArchiveMode.FULL, "user@example.com");
		add(3, 2L, ArchiveMode.INCREMENTAL, "user@example.com");

		ScopeArchives scope = only();

		assertEquals(1, scope.warnings().size());
		assertTrue(scope.warnings().getFirst().contains("missing"));
		assertFalse(scope.canMerge());
	}

	@Test
	void eachDriveIsListedSeparatelyWithItsOwnLabelAndType() {
		add(1, null, ArchiveMode.FULL, "user@example.com");
		Archive shared = new Archive(50L, "drive-1", DriveScopeType.SHARED_DRIVE, 1, null, ArchiveMode.FULL,
				RevisionMode.LATEST_ONLY, Instant.now(), "archives/Finance (drive-1)/archive-0001-full.zip", null, null,
				false);
		rows.add(shared);

		List<ScopeArchives> scopes = service.listScopes();

		assertEquals(List.of("Finance (drive-1)", "My Drive (user@example.com)"),
				scopes.stream().map(ScopeArchives::label).toList());
		assertEquals(DriveScope.sharedDrive("drive-1"), scopes.getFirst().scope());
		assertEquals(DriveScope.personal("user@example.com"), scopes.get(1).scope());
	}

	@Test
	void noArchivesMeansNoScopes() {
		assertTrue(service.listScopes().isEmpty());
	}

	private ScopeArchives only() {
		List<ScopeArchives> scopes = service.listScopes();
		assertEquals(1, scopes.size());
		return scopes.getFirst();
	}

	private static List<ArchiveState> states(ScopeArchives scope) {
		return scope.archives().stream().map(ArchiveView::state).toList();
	}

	private void add(int sequenceNumber, Long baseId, ArchiveMode mode, String scopeKey) {
		rows.add(new Archive((long) sequenceNumber, scopeKey, DriveScopeType.PERSONAL, sequenceNumber, baseId, mode,
				RevisionMode.LATEST_ONLY, Instant.now(), path(sequenceNumber), null, null, false));
	}

	private static String path(int sequenceNumber) {
		return "archives/My Drive (user@example.com)/archive-000" + sequenceNumber + "-x.zip";
	}
}
