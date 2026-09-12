package org.nm.gdrive_backup.domain.model;

import java.util.List;

public record DriveChangePage(
		List<DriveChange> changes,
		String nextPageToken,
		String newStartPageToken) {

	public DriveChangePage {
		changes = changes == null ? List.of() : List.copyOf(changes);
	}
}