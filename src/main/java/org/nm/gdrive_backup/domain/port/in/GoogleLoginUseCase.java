package org.nm.gdrive_backup.domain.port.in;

import org.nm.gdrive_backup.domain.model.GoogleLoginSession;

public interface GoogleLoginUseCase {

	GoogleLoginSession login(GoogleAuthorizationApproval approval);

	void logout(GoogleLoginSession session);
}
