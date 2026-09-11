package org.nm.gdrive_backup.domain.service;

import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.port.in.ServiceAccountAuthenticationUseCase;
import org.nm.gdrive_backup.domain.port.out.ServiceAccountCredentialPort;

public class ServiceAccountAuthenticationService implements ServiceAccountAuthenticationUseCase {

	private final ServiceAccountCredentialPort credentialPort;

	public ServiceAccountAuthenticationService(ServiceAccountCredentialPort credentialPort) {
		this.credentialPort = credentialPort;
	}

	@Override
	public ServiceAccountAccess authenticateAs(String userEmail) {
		if (userEmail == null || userEmail.isBlank()) {
			throw new IllegalArgumentException("userEmail must not be blank");
		}
		return credentialPort.authenticateAs(userEmail);
	}
}