package org.nm.gdrive_backup.domain.service;

import org.nm.gdrive_backup.domain.model.GoogleLoginSession;
import org.nm.gdrive_backup.domain.port.in.GoogleLoginUseCase;
import org.nm.gdrive_backup.domain.port.out.GoogleOAuthPort;

public class GoogleLoginService implements GoogleLoginUseCase {

	private final GoogleOAuthPort googleOAuthPort;

	public GoogleLoginService(GoogleOAuthPort googleOAuthPort) {
		this.googleOAuthPort = googleOAuthPort;
	}

	@Override
	public GoogleLoginSession login() {
		return googleOAuthPort.authenticate();
	}

	@Override
	public void logout(GoogleLoginSession session) {
		if (session == null) {
			throw new IllegalArgumentException("session must not be null");
		}
		googleOAuthPort.revoke(session);
	}
}
