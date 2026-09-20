package org.nm.gdrive_backup.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nm.gdrive_backup.domain.model.DriveScopeType;
import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.RevisionMode;

class SqliteArchiveAdapterTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void savesAndReadsBackEveryColumnIncludingNullableOnes() {
		SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("backup.db"));
		database.initialize();
		SqliteArchiveAdapter adapter = new SqliteArchiveAdapter(database);
		Instant createdAt = Instant.parse("2026-09-16T10:00:00Z");

		Archive saved = adapter.save(new Archive(null, "user@example.com", DriveScopeType.PERSONAL, 1, null, ArchiveMode.FULL,
				RevisionMode.LATEST_ONLY, createdAt, "archives/My Drive (user@example.com)/archive-0001-full.zip",
				null, null, false));

		assertNotNull(saved.id());
		assertEquals(List.of(saved), adapter.findByScopeKey("user@example.com"));
	}

	@Test
	void savesAnIncrementalArchiveChainedToItsBase() {
		SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("backup.db"));
		database.initialize();
		SqliteArchiveAdapter adapter = new SqliteArchiveAdapter(database);
		Archive full = adapter.save(new Archive(null, "user@example.com", DriveScopeType.PERSONAL, 1, null, ArchiveMode.FULL,
				RevisionMode.LATEST_ONLY, Instant.parse("2026-09-16T10:00:00Z"), "archives/scope/archive-0001-full.zip",
				null, null, false));

		Archive incremental = adapter.save(new Archive(null, "user@example.com", DriveScopeType.PERSONAL, 2, full.id(), ArchiveMode.INCREMENTAL,
				RevisionMode.LATEST_ONLY, Instant.parse("2026-09-16T11:00:00Z"),
				"archives/scope/archive-0002-incremental.zip", "token-1", "token-2", false));

		assertEquals(List.of(full, incremental), adapter.findByScopeKey("user@example.com"));
	}

	@Test
	void findByScopeKeyOrdersBySequenceNumberAndExcludesOtherScopes() {
		SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("backup.db"));
		database.initialize();
		SqliteArchiveAdapter adapter = new SqliteArchiveAdapter(database);
		Archive second = adapter.save(archive("user@example.com", 2));
		Archive first = adapter.save(archive("user@example.com", 1));
		adapter.save(archive("drive-1", 1));

		assertEquals(List.of(first, second), adapter.findByScopeKey("user@example.com"));
	}

	@Test
	void keepsTheScopeTypeOfSharedDriveArchives() {
		SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("backup.db"));
		database.initialize();
		SqliteArchiveAdapter adapter = new SqliteArchiveAdapter(database);

		adapter.save(archive("drive-1", 1));

		assertEquals(DriveScopeType.SHARED_DRIVE, adapter.findByScopeKey("drive-1").getFirst().scopeType());
	}

	@Test
	void findsEveryArchiveOrderedByScopeThenSequence() {
		SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("backup.db"));
		database.initialize();
		SqliteArchiveAdapter adapter = new SqliteArchiveAdapter(database);
		Archive b2 = adapter.save(archive("user@example.com", 2));
		Archive a1 = adapter.save(archive("drive-1", 1));
		Archive b1 = adapter.save(archive("user@example.com", 1));

		assertEquals(List.of(a1, b1, b2), adapter.findAll());
	}

	@Test
	void findsTheSourceArchivesOfAMergedArchive() {
		SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("backup.db"));
		database.initialize();
		SqliteArchiveAdapter adapter = new SqliteArchiveAdapter(database);
		Archive first = adapter.save(archive("user@example.com", 1));
		Archive second = adapter.save(archive("user@example.com", 2));
		Archive merged = adapter.save(archive("user@example.com", 3));
		new SqliteSyncCommitAdapter(database).commit(new org.nm.gdrive_backup.domain.model.PendingCommit(
				archive("user@example.com", 4), List.of(), List.of(), List.of(), null,
				List.of(first.id(), second.id())));
		long mergedId = adapter.findByScopeKey("user@example.com").getLast().id();

		assertEquals(List.of(first.id(), second.id()), adapter.findSourceArchiveIds(mergedId));
		assertEquals(List.of(), adapter.findSourceArchiveIds(merged.id()));
	}

	private static Archive archive(String scopeKey, int sequenceNumber) {
		DriveScopeType scopeType = scopeKey.contains("@") ? DriveScopeType.PERSONAL : DriveScopeType.SHARED_DRIVE;
		return new Archive(null, scopeKey, scopeType, sequenceNumber, null, ArchiveMode.FULL, RevisionMode.LATEST_ONLY,
				Instant.parse("2026-09-16T10:00:00Z"), "archives/scope/archive.zip", null, null, false);
	}
}
