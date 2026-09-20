package org.nm.gdrive_backup.adapter.in.javafx;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.ArchiveState;
import org.nm.gdrive_backup.domain.model.ArchiveView;
import org.nm.gdrive_backup.domain.model.DeletionPlan;
import org.nm.gdrive_backup.domain.model.DeletionResult;
import org.nm.gdrive_backup.domain.model.MergeResult;
import org.nm.gdrive_backup.domain.model.ScopeArchives;

/** Wording and formatting for the Archive manager, kept free of JavaFX so it can be unit tested. */
final class ArchiveManagerText {

	private static final DateTimeFormatter CREATED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	private ArchiveManagerText() {
	}

	static String kind(ArchiveMode mode) {
		return switch (mode) {
			case FULL -> "Full";
			case INCREMENTAL -> "Incremental";
			case MERGED_FULL -> "Merged full";
		};
	}

	static String state(ArchiveView view) {
		if (view.fileMissing()) {
			return "MISSING";
		}
		return switch (view.state()) {
			case CHAIN_ROOT -> "Current chain (start)";
			case CHAIN_INCREMENTAL -> "Current chain";
			case OBSOLETE -> "Obsolete (merged)";
			case PREVIOUS_CHAIN -> "Earlier chain";
		};
	}

	static String created(Instant instant, ZoneId zone) {
		return CREATED.format(instant.atZone(zone));
	}

	static String size(Long bytes) {
		if (bytes == null) {
			return "-";
		}
		if (bytes < 1024) {
			return bytes + " B";
		}
		String[] units = { "KB", "MB", "GB", "TB" };
		double value = bytes;
		int unit = -1;
		while (value >= 1024 && unit < units.length - 1) {
			value /= 1024;
			unit++;
		}
		return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit]);
	}

	/**
	 * The drive's display name without the " (key)" suffix the archive folder adds. The use cases take the bare name
	 * and re-add the suffix, so passing the folder label back would double it.
	 */
	static String displayName(String label, String scopeKey) {
		String suffix = " (" + scopeKey + ")";
		return label.endsWith(suffix) ? label.substring(0, label.length() - suffix.length()) : label;
	}

	static String warnings(List<String> warnings) {
		return String.join("\n", warnings);
	}

	static String mergeConfirmation(ScopeArchives scope) {
		long inChain = scope.archives().stream()
				.filter(view -> view.state() == ArchiveState.CHAIN_ROOT || view.state() == ArchiveState.CHAIN_INCREMENTAL)
				.count();
		return "Build one full backup for " + scope.label() + " from its " + inChain + " current archives "
				+ "(the base and " + (inChain - 1) + " incremental" + (inChain - 1 == 1 ? "" : "s") + ")?\n\n"
				+ "This reads only the archives already on disk, not Google Drive. Nothing is deleted: the new full "
				+ "becomes the start of the chain and later incremental backups continue from it. You can delete the "
				+ "archives it replaces afterwards.";
	}

	static String mergeResult(MergeResult result) {
		if (result.cancelled()) {
			return "Merge cancelled. Nothing was written.";
		}
		return "Merged into archive " + result.archive().sequenceNumber() + " (" + result.archive().archivePath() + ").";
	}

	static String deletionSummary(DeletionPlan plan) {
		StringBuilder text = new StringBuilder();
		if (plan.verified()) {
			text.append("Verification passed: the merged full was read completely and matches its manifest.\n\n");
		} else {
			text.append("Verification FAILED. Nothing can be deleted until this is resolved:\n");
			plan.verificationProblems().forEach(problem -> text.append("  - ").append(problem).append('\n'));
			text.append('\n');
		}
		text.append("Archives that would be deleted (").append(plan.obsolete().size()).append(", ")
				.append(size(plan.totalBytes())).append("):\n");
		plan.obsolete().forEach(archive -> text.append("  ").append(archive.path()).append("  (")
				.append(size(archive.sizeBytes())).append(")\n"));
		text.append("\nIn the database: ").append(plan.capturesToRepoint()).append(" content records move to the merged "
				+ "full, ").append(plan.capturesToRemove()).append(" records of older revisions are removed, and ")
				.append(plan.eventsToRepoint()).append(" history events are kept.\n");
		if (!plan.lostContent().isEmpty()) {
			text.append("\nContent that will no longer exist anywhere (").append(plan.lostContent().size())
					.append(" files):\n");
			plan.lostContent().forEach(lost -> text.append("  ").append(lost.name()).append(" - ").append(lost.reason())
					.append('\n'));
			text.append("If one of these files is restored in Drive, the next backup captures it again.\n");
		}
		return text.toString();
	}

	static String deletionResult(DeletionResult result) {
		StringBuilder text = new StringBuilder("Deleted ").append(result.deletedFiles()).append(" archive files, freeing ")
				.append(size(result.freedBytes())).append('.');
		if (!result.filesThatCouldNotBeDeleted().isEmpty()) {
			text.append("\n\nThese files could not be removed and are no longer used; delete them by hand:\n");
			result.filesThatCouldNotBeDeleted().forEach(file -> text.append("  ").append(file).append('\n'));
		}
		return text.toString();
	}
}
