package org.nm.gdrive_backup.domain.model;

import java.time.Instant;

/**
 * The running build's identity, shown in the UI so the admin can tell which version they are looking at. Both
 * fields are nullable: an IDE/Maven run with no {@code build-info.properties} on the classpath has neither.
 */
public record ApplicationInfo(String version, Instant buildTime) {

	private static final ApplicationInfo UNKNOWN = new ApplicationInfo(null, null);

	public static ApplicationInfo unknown() {
		return UNKNOWN;
	}
}
