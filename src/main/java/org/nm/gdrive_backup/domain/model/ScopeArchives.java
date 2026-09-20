package org.nm.gdrive_backup.domain.model;

import java.util.List;

/**
 * A drive's archives in sequence order, with what is wrong with its current chain ({@code warnings}) and what the
 * admin may do: {@code canMerge} when the chain has an incremental and no problem, {@code hasObsolete} when a merge
 * left archives that can be deleted.
 */
public record ScopeArchives(
		DriveScope scope,
		String label,
		List<ArchiveView> archives,
		List<String> warnings,
		boolean canMerge,
		boolean hasObsolete) {
}
