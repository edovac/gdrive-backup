package org.nm.gdrive_backup.domain.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.port.out.ArchivePort;

/** A drive's current chain plus the archives a merge in that chain made obsolete. */
record ArchiveChainInspector(ArchiveChainResolver.Chain chain, Set<Long> chainIds, Set<Long> obsoleteIds) {

	/** {@code scopeArchives} must be one drive's archives and must not be empty. */
	static ArchiveChainInspector inspect(List<Archive> scopeArchives, ArchivePort archivePort) {
		ArchiveChainResolver.Chain chain = ArchiveChainResolver.chainOf(scopeArchives);
		Set<Long> chainIds = new HashSet<>();
		chain.archives().forEach(archive -> chainIds.add(archive.id()));
		return new ArchiveChainInspector(chain, chainIds, obsoleteArchives(chain.archives(), scopeArchives, chainIds,
				archivePort));
	}

	/** Archives consumed by a merge in the current chain, followed transitively down through earlier merges. */
	private static Set<Long> obsoleteArchives(List<Archive> chain, List<Archive> all, Set<Long> chainIds,
			ArchivePort archivePort) {
		Set<Long> existing = new HashSet<>();
		all.forEach(archive -> existing.add(archive.id()));
		Set<Long> obsolete = new HashSet<>();
		List<Long> pending = new ArrayList<>();
		for (Archive archive : chain) {
			pending.addAll(archivePort.findSourceArchiveIds(archive.id()));
		}
		Set<Long> visited = new HashSet<>();
		while (!pending.isEmpty()) {
			Long id = pending.removeLast();
			if (!visited.add(id) || chainIds.contains(id) || !existing.contains(id)) {
				continue;
			}
			obsolete.add(id);
			pending.addAll(archivePort.findSourceArchiveIds(id));
		}
		return obsolete;
	}
}
