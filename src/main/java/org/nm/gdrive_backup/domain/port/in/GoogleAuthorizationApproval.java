package org.nm.gdrive_backup.domain.port.in;

import java.net.URI;

@FunctionalInterface
public interface GoogleAuthorizationApproval {

	boolean approve(URI authorizationUri);
}