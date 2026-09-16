package org.nm.gdrive_backup.domain.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.nm.gdrive_backup.domain.model.StoredFile;

/**
 * Resolves the real Drive-shaped folder path for each eligible file, from the id-based
 * parent chains Drive reports. A file with multiple parents uses only the first
 * (an accepted, documented lossy flattening); an unresolvable ancestor, or a chain deep
 * or cyclic enough to trip the depth cap, places the file at the archive's root instead
 * of failing the whole archive over one bad row.
 */
final class FlatTreePathResolver {

	private static final String ROOT_KEY = "";
	private static final int MAX_DEPTH = 100;

	private final Map<String, StoredFile> allFilesById;
	private final Map<String, List<StoredFile>> childrenByParentKey = new HashMap<>();
	private final Map<String, Map<String, String>> disambiguatedNamesByParentKey = new HashMap<>();
	private final Map<String, String> resolvedFolderPaths = new HashMap<>();

	FlatTreePathResolver(Map<String, StoredFile> allFilesById) {
		this.allFilesById = allFilesById;
		for (StoredFile file : allFilesById.values()) {
			childrenByParentKey.computeIfAbsent(parentKeyOf(file), key -> new ArrayList<>()).add(file);
		}
	}

	/** The materialized, sanitized, collision-free path for a non-folder file, relative to the archive root. */
	String resolveEntryName(StoredFile file) {
		String parentKey = parentKeyOf(file);
		String parentPath = parentKey.equals(ROOT_KEY) ? "" : resolveFolderPath(parentKey, new HashSet<>(), 0);
		String name = namesFor(parentKey).get(file.fileId());
		return parentPath.isEmpty() ? name : parentPath + "/" + name;
	}

	private String resolveFolderPath(String folderId, Set<String> visiting, int depth) {
		String cached = resolvedFolderPaths.get(folderId);
		if (cached != null) {
			return cached;
		}
		StoredFile folder = allFilesById.get(folderId);
		String parentKey = parentKeyOf(folder);
		String parentPath;
		if (parentKey.equals(ROOT_KEY) || depth >= MAX_DEPTH || visiting.contains(folderId)) {
			parentPath = "";
		} else {
			visiting.add(folderId);
			parentPath = resolveFolderPath(parentKey, visiting, depth + 1);
			visiting.remove(folderId);
		}
		String name = namesFor(parentKey).get(folderId);
		String path = parentPath.isEmpty() ? name : parentPath + "/" + name;
		resolvedFolderPaths.put(folderId, path);
		return path;
	}

	private Map<String, String> namesFor(String parentKey) {
		return disambiguatedNamesByParentKey.computeIfAbsent(parentKey,
				key -> disambiguate(childrenByParentKey.getOrDefault(key, List.of())));
	}

	/** One shared name assignment per parent (not per leaf) so siblings never disagree on who gets suffixed. */
	private static Map<String, String> disambiguate(List<StoredFile> siblings) {
		Map<String, String> names = new HashMap<>();
		Set<String> used = new HashSet<>();
		List<StoredFile> ordered = new ArrayList<>(siblings);
		ordered.sort(Comparator.comparing(StoredFile::fileId));
		for (StoredFile sibling : ordered) {
			String base = ArchiveNaming.sanitizeSegment(sibling.name());
			String candidate = base;
			int suffix = 2;
			while (!used.add(candidate)) {
				candidate = base + " (" + suffix + ")";
				suffix++;
			}
			names.put(sibling.fileId(), candidate);
		}
		return names;
	}

	private String parentKeyOf(StoredFile file) {
		String parents = file.parents();
		if (parents == null || parents.isBlank()) {
			return ROOT_KEY;
		}
		// A file with no parents, or whose first parent is never itself a synced row (the
		// real Drive root, or a Shared Drive's own container), sits at the archive root.
		String firstParentId = parents.split(",", 2)[0];
		return firstParentId.isBlank() || !allFilesById.containsKey(firstParentId) ? ROOT_KEY : firstParentId;
	}
}
