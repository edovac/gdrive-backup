package org.nm.gdrive_backup.domain.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.ArchiveState;
import org.nm.gdrive_backup.domain.model.ArchiveView;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.ScopeArchives;
import org.nm.gdrive_backup.domain.port.in.ArchiveCatalogUseCase;
import org.nm.gdrive_backup.domain.port.out.ArchivePort;
import org.nm.gdrive_backup.domain.port.out.ArchiveStoragePort;

/** Read-only view of every drive's archive chain: which archives are current, obsolete or left from an older chain. */
public class ArchiveCatalogService implements ArchiveCatalogUseCase {

	private final ArchivePort archivePort;
	private final ArchiveStoragePort storagePort;

	public ArchiveCatalogService(ArchivePort archivePort, ArchiveStoragePort storagePort) {
		this.archivePort = archivePort;
		this.storagePort = storagePort;
	}

	@Override
	public List<ScopeArchives> listScopes() {
		Map<String, List<Archive>> byScope = new LinkedHashMap<>();
		for (Archive archive : archivePort.findAll()) {
			byScope.computeIfAbsent(archive.scopeKey(), key -> new ArrayList<>()).add(archive);
		}
		return byScope.values().stream()
				.map(this::describe)
				.sorted(Comparator.comparing(ScopeArchives::label, String.CASE_INSENSITIVE_ORDER))
				.toList();
	}

	private ScopeArchives describe(List<Archive> archives) {
		List<Archive> ordered = archives.stream().sorted(Comparator.comparingInt(Archive::sequenceNumber)).toList();
		Archive any = ordered.getFirst();
		DriveScope scope = new DriveScope(any.scopeKey(), any.scopeType());

		ArchiveChainInspector inspection = ArchiveChainInspector.inspect(ordered, archivePort);
		ArchiveChainResolver.Chain chain = inspection.chain();
		Set<Long> chainIds = inspection.chainIds();
		Set<Long> obsoleteIds = inspection.obsoleteIds();

		List<String> warnings = new ArrayList<>();
		if (chain.problem() != null) {
			warnings.add(chain.problem());
		}
		List<ArchiveView> views = new ArrayList<>();
		for (Archive archive : ordered) {
			OptionalLong size = storagePort.sizeOf(archive.archivePath());
			ArchiveState state = stateOf(archive, chain.archives(), chainIds, obsoleteIds);
			boolean inChain = state == ArchiveState.CHAIN_ROOT || state == ArchiveState.CHAIN_INCREMENTAL;
			if (size.isEmpty() && inChain) {
				warnings.add("Archive " + archive.sequenceNumber() + " (" + archive.archivePath()
						+ ") is missing from disk");
			}
			views.add(new ArchiveView(archive, state, size.isEmpty(), size.isPresent() ? size.getAsLong() : null));
		}

		boolean rooted = chain.problem() == null && isRoot(chain.archives().getFirst());
		boolean canMerge = rooted && chain.archives().size() > 1 && warnings.isEmpty();
		boolean hasEarlierChains = warnings.isEmpty()
				&& views.stream().anyMatch(view -> view.state() == ArchiveState.PREVIOUS_CHAIN);
		return new ScopeArchives(scope, labelOf(any), views, warnings, canMerge, !obsoleteIds.isEmpty(),
				hasEarlierChains);
	}

	private static ArchiveState stateOf(Archive archive, List<Archive> chain, Set<Long> chainIds, Set<Long> obsoleteIds) {
		if (chainIds.contains(archive.id())) {
			return archive.id().equals(chain.getFirst().id()) && isRoot(archive)
					? ArchiveState.CHAIN_ROOT
					: ArchiveState.CHAIN_INCREMENTAL;
		}
		return obsoleteIds.contains(archive.id()) ? ArchiveState.OBSOLETE : ArchiveState.PREVIOUS_CHAIN;
	}

	private static boolean isRoot(Archive archive) {
		return archive.mode() == ArchiveMode.FULL || archive.mode() == ArchiveMode.MERGED_FULL;
	}

	/** The drive's folder name under archives/, which is already "My Drive (email)" or "Name (drive_id)". */
	static String labelOf(Archive archive) {
		String[] segments = archive.archivePath() == null ? new String[0] : archive.archivePath().split("/");
		return segments.length >= 3 ? segments[1] : archive.scopeKey();
	}
}
