package org.nm.gdrive_backup.domain.port.out;

import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;

public interface ServiceAccountCredentialPort {

	ServiceAccountAccess authenticateAs(String userEmail);
}