package org.nm.gdrive_backup.adapter.in.javafx;

import org.nm.gdrive_backup.domain.model.WorkspaceUser;

/** Wording for the common session header, kept free of JavaFX so it can be unit tested. */
final class SessionHeaderText {

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
}
