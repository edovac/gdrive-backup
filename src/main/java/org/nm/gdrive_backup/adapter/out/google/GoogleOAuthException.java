package org.nm.gdrive_backup.adapter.out.google;

public class GoogleOAuthException extends RuntimeException {

	public GoogleOAuthException(String message, Throwable cause) {
		super(message, cause);
	}

	public GoogleOAuthException(String message) {
		super(message);
	}
}
