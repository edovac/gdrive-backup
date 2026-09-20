package org.nm.gdrive_backup.adapter.out.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import org.nm.gdrive_backup.domain.model.DeletionCommit;
import org.nm.gdrive_backup.domain.port.out.ArchiveDeletionCommitPort;
import org.springframework.stereotype.Component;

/** The database side of deleting obsolete archives: all of it lands in one transaction, or none of it does. */
@Component
public class SqliteArchiveDeletionCommitAdapter implements ArchiveDeletionCommitPort {

	private final SqliteDatabase database;

	public SqliteArchiveDeletionCommitAdapter(SqliteDatabase database) {
		this.database = database;
	}

	@Override
	public void apply(DeletionCommit commit) {
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try {
				for (Map.Entry<Long, String> repoint : commit.captureIdToNewEntry().entrySet()) {
					repointCapture(connection, repoint.getKey(), commit.mergedArchiveId(), repoint.getValue());
				}
				for (Long captureId : commit.captureIdsToRemove()) {
					removeCapture(connection, captureId);
				}
				for (Long archiveId : commit.obsoleteArchiveIds()) {
					repointEvents(connection, archiveId, commit.mergedArchiveId());
				}
				for (Long archiveId : commit.obsoleteArchiveIds()) {
					removeArchive(connection, archiveId);
				}
				connection.commit();
			} catch (SQLException | RuntimeException exception) {
				connection.rollback();
				throw exception;
			}
		} catch (SQLException exception) {
			throw new IllegalStateException("Unable to delete archives in SQLite", exception);
		}
	}

	private static void repointCapture(Connection connection, long captureId, long mergedArchiveId, String entryName)
			throws SQLException {
		try (var statement = connection.prepareStatement(
				"UPDATE file_captures SET archive_id = ?, entry_name = ? WHERE id = ?")) {
			statement.setLong(1, mergedArchiveId);
			statement.setString(2, entryName);
			statement.setLong(3, captureId);
			statement.executeUpdate();
		}
	}

	/** A file that loses its current capture has no current version until it is captured again. */
	private static void removeCapture(Connection connection, long captureId) throws SQLException {
		try (var statement = connection.prepareStatement(
				"UPDATE files SET current_version_id = NULL WHERE current_version_id = ?")) {
			statement.setLong(1, captureId);
			statement.executeUpdate();
		}
		try (var statement = connection.prepareStatement("DELETE FROM file_captures WHERE id = ?")) {
			statement.setLong(1, captureId);
			statement.executeUpdate();
		}
	}

	private static void repointEvents(Connection connection, long fromArchiveId, long mergedArchiveId)
			throws SQLException {
		try (var statement = connection.prepareStatement(
				"UPDATE file_events SET archive_id = ? WHERE archive_id = ?")) {
			statement.setLong(1, mergedArchiveId);
			statement.setLong(2, fromArchiveId);
			statement.executeUpdate();
		}
	}

	private static void removeArchive(Connection connection, long archiveId) throws SQLException {
		try (var statement = connection.prepareStatement(
				"DELETE FROM archive_sources WHERE archive_id = ? OR source_archive_id = ?")) {
			statement.setLong(1, archiveId);
			statement.setLong(2, archiveId);
			statement.executeUpdate();
		}
		try (var statement = connection.prepareStatement("DELETE FROM archives WHERE id = ?")) {
			statement.setLong(1, archiveId);
			statement.executeUpdate();
		}
	}
}
