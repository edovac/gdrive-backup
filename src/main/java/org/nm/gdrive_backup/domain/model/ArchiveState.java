package org.nm.gdrive_backup.domain.model;

/** Where an archive stands relative to its drive's current chain (the one the next incremental extends). */
public enum ArchiveState {
	/** The full or merged full the current chain starts from. */
	CHAIN_ROOT,
	/** An incremental in the current chain. */
	CHAIN_INCREMENTAL,
	/** Consumed by a merge in the current chain: its content is carried by the merged full, and it can be deleted. */
	OBSOLETE,
	/** Part of an older chain that a later from-scratch full left behind; deleted only as a whole, on request. */
	PREVIOUS_CHAIN
}
