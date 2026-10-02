package org.nm.gdrive_backup.adapter.in.javafx;

/** Wording for the Settings view, kept free of JavaFX so it can be unit tested. */
final class SettingsText {

	private SettingsText() {
	}

	static String serviceAccountKeyStatus(boolean configured) {
		return configured ? "Service-account key: configured" : "Service-account key: not configured";
	}

	static String oauthClientSecretsStatus(boolean configured) {
		return configured ? "OAuth client secrets: configured" : "OAuth client secrets: not configured";
	}

	static String importSucceeded(String label) {
		return label + " imported.";
	}

	static String importFailed(String label, String reason) {
		return "Unable to import " + label + ": " + reason;
	}

	static String cleared(String label) {
		return label + " cleared.";
	}

	static String clearFailed(String label, String reason) {
		return "Unable to clear " + label + ": " + reason;
	}

	static String projectIdSaved() {
		return "Project id saved.";
	}

	static String projectIdSaveFailed(String reason) {
		return "Unable to save project id: " + reason;
	}

	static String downloadConcurrencyHint(int minimum, int maximum) {
		return "Files downloaded from Drive at once (" + minimum + " to " + maximum + "). Higher is faster until "
				+ "Drive starts rate-limiting. Applies to the next backup and resets when the app restarts.";
	}

	static String downloadConcurrencySaved(int value) {
		return "Downloading " + value + (value == 1 ? " file" : " files") + " at once from the next backup.";
	}

	static String downloadConcurrencySaveFailed(String reason) {
		return "Unable to change the number of parallel downloads: " + reason;
	}

	/** The root cause's message, which is what the admin can act on. */
	static String reason(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
	}
}
