package org.nm.gdrive_backup.domain.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.DriveScopeType;
import org.nm.gdrive_backup.domain.model.StoredFile;

/**
 * Builds a file's full path as Drive shows it, such as {@code My Drive/Reports/Q3.pdf}, for display. Unlike
 * {@link FlatTreePathResolver} it keeps the real names (no sanitizing or collision suffixes), since nothing is
 * written to disk under this path. A file with several parents uses the first. A parent that cannot be looked up
 * is taken to be the drive's root (Drive reports the root, or a Shared Drive's container, as a parent that is not a
 * file of its own), and a chain too deep or cyclic to follow is cut with a leading ellipsis. Safe to call from
 * several threads.
 */
final class DrivePathResolver {

	private static final String PERSONAL_ROOT = "My Drive";
	private static final String CUT = "…";
	private static final int MAX_DEPTH = 100;

	private final String rootLabel;
	private final Function<String, StoredFile> lookup;
	private final Map<String, String> folderPaths = new HashMap<>();

	/** @param lookup finds a file's metadata by id, or returns {@code null} when it is not known */
	DrivePathResolver(String rootLabel, Function<String, StoredFile> lookup) {
		this.rootLabel = rootLabel;
		this.lookup = lookup;
	}

	/** The label a drive's paths start with: "My Drive", or the Shared Drive's name (its id if no name is known). */
	static String rootLabel(DriveScope scope, String displayNameOrNull) {
		if (scope.type() == DriveScopeType.PERSONAL) {
			return PERSONAL_ROOT;
		}
		return displayNameOrNull == null || displayNameOrNull.isBlank() ? scope.key() : displayNameOrNull;
	}

	synchronized String pathOf(StoredFile file) {
		List<StoredFile> folders = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		String prefix = rootLabel;
		String folderId = firstParentId(file);
		while (folderId != null) {
			String known = folderPaths.get(folderId);
			if (known != null) {
				prefix = known;
				break;
			}
			if (folders.size() >= MAX_DEPTH || !seen.add(folderId)) {
				prefix = CUT;
				break;
			}
			StoredFile folder = lookup.apply(folderId);
			if (folder == null) {
				break;
			}
			folders.add(folder);
			folderId = firstParentId(folder);
		}
		String path = prefix;
		for (int i = folders.size() - 1; i >= 0; i--) {
			path = path + "/" + nameOf(folders.get(i));
			folderPaths.put(folders.get(i).fileId(), path);
		}
		return path + "/" + nameOf(file);
	}

	private static String firstParentId(StoredFile file) {
		String parents = file.parents();
		if (parents == null || parents.isBlank()) {
			return null;
		}
		String first = parents.split(",", 2)[0];
		return first.isBlank() ? null : first;
	}

	private static String nameOf(StoredFile file) {
		return file.name() == null || file.name().isBlank() ? file.fileId() : file.name();
	}
}
