package org.nm.gdrive_backup.adapter.in.javafx;

import java.util.List;

import org.nm.gdrive_backup.domain.model.AvailableDrive;
import org.nm.gdrive_backup.domain.model.BackupResult;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.DriveScopeType;

/** Wording for the backup completion summary, kept free of JavaFX so it can be unit tested. */
public final class BackupSummaryText {

	private BackupSummaryText() {
	}

	public static String summary(List<BackupResult> results, List<AvailableDrive> selectedDrives, boolean cancelled) {
		long failed = results.stream().filter(BackupResult::failed).count();
		StringBuilder text = new StringBuilder(headline(failed, cancelled));
		for (BackupResult result : results) {
			text.append("\n").append(scopeLabel(result.scope(), selectedDrives)).append(": ")
					.append(itemSummary(result));
		}
		// Results follow the selection order and a stop only ever cuts the run short, so the rest never started.
		for (AvailableDrive drive : selectedDrives.subList(Math.min(results.size(), selectedDrives.size()),
				selectedDrives.size())) {
			text.append("\n").append(drive.shared() ? "Shared: " : "").append(drive.name())
					.append(": not started");
		}
		return text.toString();
	}

	private static String headline(long failed, boolean cancelled) {
		if (cancelled) {
			return "Synchronization cancelled." + (failed > 0 ? " " + failed + " drive(s) failed." : "");
		}
		return failed == 0 ? "Synchronization complete."
				: "Synchronization complete with " + failed + " failed drive(s).";
	}

	static String itemSummary(BackupResult result) {
		if (result.failed()) {
			return "FAILED — " + result.failureMessage();
		}
		String activity = result.initialSync() ? "files inventoried" : "changes processed";
		String suffix = result.cancelled() ? " (cancelled)" : "";
		return result.processedItemCount() + " " + activity + suffix;
	}

	static String scopeLabel(DriveScope scope, List<AvailableDrive> knownDrives) {
		if (scope.type() == DriveScopeType.PERSONAL) {
			return "My Drive (" + scope.key() + ")";
		}
		return knownDrives.stream()
				.filter(drive -> drive.id().equals(scope.key()))
				.findFirst()
				.map(drive -> "Shared: " + drive.name())
				.orElse("Shared drive " + scope.key());
	}
}
