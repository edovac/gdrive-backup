package org.nm.gdrive_backup.adapter.in.javafx;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import org.nm.gdrive_backup.domain.model.WorkspaceUser;

/** Wording for the common session header, kept free of JavaFX so it can be unit tested. */
final class SessionHeaderText {

	/**
	 * A brand-neutral palette for the avatar's fallback fill, picked deterministically per email so the same admin
	 * always gets the same color. {@code String.hashCode()} is specified by the JLS, so this is stable across runs.
	 */
	private static final List<String> AVATAR_COLORS = List.of(
			"#4285f4", "#ea4335", "#fbbc04", "#34a853", "#7b1fa2", "#00838f", "#e8710a", "#3949ab");

	private SessionHeaderText() {
	}

	/** "Display Name (email)", or just the email when Google reports no name. */
	static String user(WorkspaceUser user) {
		return user.displayName().isBlank() ? user.email() : user.displayName() + " (" + user.email() + ")";
	}

	/** Shown instead of the picker when the users cannot be listed and only the configured user is known. */
	static String fixedUser(String email) {
		return email == null || email.isBlank() ? "No user selected" : email;
	}

	/** The Workspace domain from the admin's email, or {@code null} when there is none to show a favicon for. */
	static String domainOf(String email) {
		if (email == null) {
			return null;
		}
		int at = email.indexOf('@');
		if (at < 0 || at == email.length() - 1) {
			return null;
		}
		return email.substring(at + 1).toLowerCase(Locale.ROOT);
	}

	/**
	 * Google's public favicon service has no API for a Workspace org's own custom logo, so this is the closest
	 * stand-in: the domain's own favicon, at a size that reads well as a small header icon.
	 */
	static String faviconUrl(String domain) {
		if (domain == null || domain.isBlank()) {
			return null;
		}
		return "https://www.google.com/s2/favicons?sz=64&domain=" + URLEncoder.encode(domain, StandardCharsets.UTF_8);
	}

	/** The admin's display name if Drive reported one, else their email. */
	static String adminName(String email, String displayName) {
		return displayName == null || displayName.isBlank() ? email : displayName;
	}

	/** Up to two initials for the avatar fallback: from the display name, else the email's first letter. */
	static String initials(String email, String displayName) {
		if (displayName != null && !displayName.isBlank()) {
			String[] words = displayName.trim().split("\\s+");
			StringBuilder initials = new StringBuilder();
			for (int i = 0; i < words.length && initials.length() < 2; i++) {
				if (!words[i].isEmpty()) {
					initials.append(Character.toUpperCase(words[i].charAt(0)));
				}
			}
			if (!initials.isEmpty()) {
				return initials.toString();
			}
		}
		return email == null || email.isBlank() ? "?" : String.valueOf(Character.toUpperCase(email.charAt(0)));
	}

	/** A stable fallback fill color for the avatar, picked from a fixed palette by the admin's email. */
	static String avatarColor(String email) {
		if (email == null || email.isBlank()) {
			return AVATAR_COLORS.getFirst();
		}
		int index = Math.floorMod(email.hashCode(), AVATAR_COLORS.size());
		return AVATAR_COLORS.get(index);
	}
}
