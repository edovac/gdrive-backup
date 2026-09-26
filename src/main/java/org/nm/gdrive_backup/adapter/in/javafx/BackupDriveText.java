package org.nm.gdrive_backup.adapter.in.javafx;

import java.time.ZoneId;
import java.util.Locale;

import org.nm.gdrive_backup.domain.model.ArchiveView;
import org.nm.gdrive_backup.domain.model.AvailableDrive;
import org.nm.gdrive_backup.domain.model.BackupMode;
import org.nm.gdrive_backup.domain.model.BackupProgress;
import org.nm.gdrive_backup.domain.model.BackupResult;
import org.nm.gdrive_backup.domain.model.ScopeArchives;

/** Wording and per-drive status for the Backup tab's drive list, kept free of JavaFX so it can be unit tested. */
final class BackupDriveText {

	/** How a drive row's status pill should be colored. */
	enum Tone {
		OK, WARN, ERROR, INFO, NEUTRAL
	}

	/** @param detail empty when the pill's label needs no further explanation */
	record DriveStatus(String label, String detail, Tone tone) {
	}

	private BackupDriveText() {
	}

	/** The scope key a drive is matched against in the archive catalog — the same rule DriveBackupService uses. */
	static String scopeKey(AvailableDrive drive, String userEmail) {
		return drive.shared() ? drive.id() : userEmail;
	}

	static String subtitle(AvailableDrive drive, String userEmail) {
		return drive.shared() ? "Shared drive" : userEmail;
	}

	static String modeLabel(BackupMode mode) {
		return mode == BackupMode.FULL ? "Full" : "Incremental";
	}

	static String modeDescription(BackupMode mode) {
		return mode == BackupMode.FULL
				? "Re-downloads every live file into a new chain root. Slow; use after long gaps or to start clean."
				: "Only changes since the last backup. Fast; adds a delta to the chain. "
						+ "New drives get a full inventory automatically.";
	}

	static String selectionCount(int selected, int total) {
		if (total == 0) {
			return "No drives loaded";
		}
		return selected + " of " + total + " drive" + (total == 1 ? "" : "s") + " selected";
	}

	/** A drive that isn't running as part of the current job: its chain state from the archive catalog. */
	static DriveStatus idleStatus(ScopeArchives catalog, ZoneId zone) {
		if (catalog == null || catalog.archives().isEmpty()) {
			return new DriveStatus("New", "Never backed up", Tone.NEUTRAL);
		}
		ArchiveView latest = catalog.archives().get(catalog.archives().size() - 1);
		String when = "Last archive: " + ArchiveManagerText.created(latest.archive().createdAt(), zone) + " · "
				+ ArchiveManagerText.kind(latest.archive().mode()).toLowerCase(Locale.ROOT);
		if (!catalog.warnings().isEmpty()) {
			return new DriveStatus("Chain problem", when, Tone.WARN);
		}
		return new DriveStatus("OK", when, Tone.OK);
	}

	/** A drive queued in the current job but not yet reached. */
	static DriveStatus queuedStatus() {
		return new DriveStatus("Queued", "Waiting to start", Tone.NEUTRAL);
	}

	/**
	 * A drive's status while the job is running, before its {@link BackupResult} is known.
	 *
	 * @param rowIndex this drive's position (0-based) in the run's drive selection
	 */
	static DriveStatus liveStatus(int rowIndex, BackupProgress progress) {
		if (progress.completedDrives() > rowIndex) {
			return new DriveStatus("Finished", "", Tone.OK);
		}
		if (progress.driveNumber() != rowIndex + 1) {
			return queuedStatus();
		}
		return switch (progress.phase()) {
			case ENUMERATING -> new DriveStatus("Running", "Enumerating...", Tone.INFO);
			case PACKAGING -> new DriveStatus("Running", "Packaging...", Tone.INFO);
			case FINISHED -> new DriveStatus("Running", "Finishing...", Tone.INFO);
			case BACKING_UP -> new DriveStatus("Running", runningDetail(progress), Tone.INFO);
		};
	}

	private static String runningDetail(BackupProgress progress) {
		return progress.totalItems() != null
				? progress.processedItems() + " / " + progress.totalItems() + " files"
				: progress.processedItems() + " items processed";
	}

	/** A drive's final status once the job's {@link BackupResult}s are known; {@code result} is null if it never started. */
	static DriveStatus resultStatus(BackupResult result) {
		if (result == null) {
			return new DriveStatus("Not started", "", Tone.NEUTRAL);
		}
		if (result.failed()) {
			return new DriveStatus("Failed", result.failureMessage(), Tone.ERROR);
		}
		String detail = BackupSummaryText.itemSummary(result);
		return result.cancelled() ? new DriveStatus("Cancelled", detail, Tone.WARN)
				: new DriveStatus("Done", detail, Tone.OK);
	}
}
