package org.nm.gdrive_backup.domain.port.in;

import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;

public interface ServiceAccountAuthenticationUseCase {

	ServiceAccountAccess authenticateAs(String userEmail);
}