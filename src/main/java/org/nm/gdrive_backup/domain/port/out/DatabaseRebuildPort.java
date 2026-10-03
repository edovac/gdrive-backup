package org.nm.gdrive_backup.domain.port.out;

import java.util.Optional;

import org.nm.gdrive_backup.domain.model.DatabaseStatus;

/**
 * Builds a new database beside the current one and swaps it in only when it is complete, so a failed or cancelled
 * rebuild leaves the current database exactly as it was. While a fresh database is being built every other
 * database port writes to it.
 */
public interface DatabaseRebuildPort {

	DatabaseStatus status();

	/** Starts an empty database with the current schema and routes every later database access to it. */
	void startFresh();

	/** Keeps the old database under a dated name, makes the fresh one the database and returns the old one's file name. */
	Optional<String> complete();

	/** Drops the fresh database and routes database access back to the current one. */
	void abort();
}
