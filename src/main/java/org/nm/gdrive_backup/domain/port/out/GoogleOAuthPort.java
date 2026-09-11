package org.nm.gdrive_backup.domain.port.out;

import org.nm.gdrive_backup.domain.model.GoogleLoginSession;
import org.nm.gdrive_backup.domain.port.in.GoogleAuthorizationApproval;

public interface GoogleOAuthPort {

	GoogleLoginSession authenticate(GoogleAuthorizationApproval approval);

	void revoke(GoogleLoginSession session);
}
