package org.nm.gdrive_backup.domain.port.out;

import org.nm.gdrive_backup.domain.model.GoogleLoginSession;

public interface GoogleOAuthPort {

	GoogleLoginSession authenticate();

	void revoke(GoogleLoginSession session);
}
