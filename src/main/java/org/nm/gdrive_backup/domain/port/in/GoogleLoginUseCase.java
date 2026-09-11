package org.nm.gdrive_backup.domain.port.in;

import org.nm.gdrive_backup.domain.model.GoogleLoginSession;

public interface GoogleLoginUseCase {

	GoogleLoginSession login();

	void logout(GoogleLoginSession session);
}
