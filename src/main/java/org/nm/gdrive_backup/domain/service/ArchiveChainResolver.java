package org.nm.gdrive_backup.domain.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveChainException;

/** Finds the chain a scope's next incremental would extend: the latest archive and everything it descends from. */
final class ArchiveChainResolver {

	private ArchiveChainResolver() {
	}

	/** The current chain, root first. Needs at least one incremental on top of the root to be worth merging. */
	static List<Archive> currentChain(List<Archive> all) {
		if (all.isEmpty()) {
			throw new ArchiveChainException("This drive has no archives yet");
		}
		Map<Long, Archive> byId = new HashMap<>();
		for (Archive archive : all) {
			byId.put(archive.id(), archive);
		}
		Archive tip = all.stream().max(Comparator.comparingInt(Archive::sequenceNumber)).orElseThrow();
		List<Archive> chain = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		Archive current = tip;
		while (true) {
			if (!seen.add(current.id())) {
				throw new ArchiveChainException("Archive chain loops back on itself at archive "
						+ current.sequenceNumber());
			}
			chain.add(current);
			if (current.baseArchiveId() == null) {
				break;
			}
			Archive base = byId.get(current.baseArchiveId());
			if (base == null) {
				throw new ArchiveChainException("Archive " + current.sequenceNumber() + " chains onto archive id "
						+ current.baseArchiveId() + ", which is missing from the archive records");
			}
			current = base;
		}
		Collections.reverse(chain);
		if (chain.size() == 1) {
			throw new ArchiveChainException("Nothing to merge: the current chain (archive "
					+ tip.sequenceNumber() + ") has no incremental archives");
		}
		return chain;
	}
}
