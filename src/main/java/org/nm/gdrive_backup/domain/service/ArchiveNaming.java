package org.nm.gdrive_backup.domain.service;

import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.DriveScopeType;

/** Archive folder/file naming: backupRoot/archives/&lt;scopeFolder&gt;/archive-&lt;sequence&gt;-&lt;mode&gt;.zip */
final class ArchiveNaming {

	private ArchiveNaming() {
	}

	static String scopeFolderName(DriveScope scope, String displayNameOrNull) {
		String label = scope.type() == DriveScopeType.PERSONAL
				? "My Drive (" + scope.key() + ")"
				: (displayNameOrNull != null ? displayNameOrNull : "Shared Drive") + " (" + scope.key() + ")";
		return sanitizeSegment(label);
	}

	static String archiveFileName(int sequenceNumber, ArchiveMode mode) {
		return "archive-%04d-%s.zip".formatted(sequenceNumber, kebabCase(mode));
	}

	/** Only characters genuinely illegal on Windows are replaced, so real names stay readable. */
	static String sanitizeSegment(String raw) {
		return raw.replaceAll("[\\\\/:*?\"<>|]", "_");
	}

	private static String kebabCase(ArchiveMode mode) {
		return mode.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
	}
}
