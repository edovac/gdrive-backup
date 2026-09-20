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

	/**
	 * The chain found by walking base links down from the latest archive, root first, and what stopped the walk
	 * short of a root ({@code problem}, or null when it reached one).
	 */
	record Chain(List<Archive> archives, String problem) {
	}

	/** Walks the links without throwing, so a catalog can still show a broken chain. {@code all} must not be empty. */
	static Chain chainOf(List<Archive> all) {
		Map<Long, Archive> byId = new HashMap<>();
		for (Archive archive : all) {
			byId.put(archive.id(), archive);
		}
		Archive tip = all.stream().max(Comparator.comparingInt(Archive::sequenceNumber)).orElseThrow();
		List<Archive> chain = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		String problem = null;
		Archive current = tip;
		while (true) {
			if (!seen.add(current.id())) {
				problem = "Archive chain loops back on itself at archive " + current.sequenceNumber();
				break;
			}
			chain.add(current);
			if (current.baseArchiveId() == null) {
				break;
			}
			Archive base = byId.get(current.baseArchiveId());
			if (base == null) {
				problem = "Archive " + current.sequenceNumber() + " chains onto archive id " + current.baseArchiveId()
						+ ", which is missing from the archive records";
				break;
			}
			current = base;
		}
		Collections.reverse(chain);
		return new Chain(chain, problem);
	}

	/** The current chain, root first. Needs at least one incremental on top of the root to be worth merging. */
	static List<Archive> currentChain(List<Archive> all) {
		if (all.isEmpty()) {
			throw new ArchiveChainException("This drive has no archives yet");
		}
		Chain chain = chainOf(all);
		if (chain.problem() != null) {
			throw new ArchiveChainException(chain.problem());
		}
		if (chain.archives().size() == 1) {
			throw new ArchiveChainException("Nothing to merge: the current chain (archive "
					+ chain.archives().getFirst().sequenceNumber() + ") has no incremental archives");
		}
		return chain.archives();
	}
}
