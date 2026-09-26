package org.nm.gdrive_backup.adapter.in.javafx;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.nm.gdrive_backup.domain.model.ApplicationInfo;

/**
 * Wording for the app version, kept free of JavaFX so it can be unit tested. Public because
 * {@code JavaFxApplication}, outside this package, uses {@link #versionLabel} for the login screen.
 */
public final class ApplicationInfoText {

	private static final DateTimeFormatter BUILT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private ApplicationInfoText() {
	}

	/** Short label for the header and the login screen, e.g. "v0.1.0". */
	public static String versionLabel(ApplicationInfo info) {
		return info.version() == null || info.version().isBlank() ? "development build" : "v" + info.version();
	}

	static String status(ApplicationInfo info) {
		return "Google Drive Backup " + versionLabel(info);
	}

	/** Detail lines for the Technical info tab's Application card. */
	static List<String> lines(ApplicationInfo info) {
		return lines(info, ZoneId.systemDefault());
	}

	static List<String> lines(ApplicationInfo info, ZoneId zone) {
		String built = info.buildTime() == null ? "Build time: unknown"
				: "Built: " + BUILT.format(info.buildTime().atZone(zone));
		return List.of(built);
	}
}
