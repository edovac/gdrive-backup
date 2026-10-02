package org.nm.gdrive_backup.domain.model;

import java.util.List;

/**
 * What a run does to the download-failure report, applied with the rest of its commit: {@code failed} are the files
 * it skipped (they replace any open failure of the same file) and {@code resolvedFileIds} are files whose open
 * failures are closed because the run captured them or they no longer need a backup.
 */
public record FailureChanges(String scopeKey, List<DownloadFailure> failed, List<String> resolvedFileIds) {

	public static final FailureChanges NONE = new FailureChanges(null, List.of(), List.of());

	public boolean isEmpty() {
		return failed.isEmpty() && resolvedFileIds.isEmpty();
	}
}
