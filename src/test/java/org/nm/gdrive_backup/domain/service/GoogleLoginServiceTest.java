package org.nm.gdrive_backup.domain.service;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.GoogleLoginSession;
import org.nm.gdrive_backup.domain.port.in.GoogleAuthorizationApproval;
import org.nm.gdrive_backup.domain.port.out.GoogleOAuthPort;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GoogleLoginServiceTest {

	@Test
	void loginReturnsOpaqueSessionFromOAuthPort() {
		GoogleLoginSession expected = session();
		FakeGoogleOAuthPort port = new FakeGoogleOAuthPort(expected);

		GoogleLoginSession actual = new GoogleLoginService(port).login(uri -> true);

		assertSame(expected, actual);
	}

	@Test
	void logoutRevokesTheSessionThroughOAuthPort() {
		GoogleLoginSession expected = session();
		FakeGoogleOAuthPort port = new FakeGoogleOAuthPort(expected);
		GoogleLoginService service = new GoogleLoginService(port);

		service.logout(expected);

		assertSame(expected, port.revokedSession);
	}

	@Test
	void logoutRejectsNullSession() {
		GoogleLoginService service = new GoogleLoginService(new FakeGoogleOAuthPort(session()));

		assertThrows(IllegalArgumentException.class, () -> service.logout(null));
	}

	private static GoogleLoginSession session() {
		return new GoogleLoginSession(UUID.randomUUID(), Instant.now().plusSeconds(300),
				Set.of("https://www.googleapis.com/auth/drive.readonly"), "admin@example.com");
	}

	private static final class FakeGoogleOAuthPort implements GoogleOAuthPort {

		private final GoogleLoginSession session;
		private GoogleLoginSession revokedSession;

		private FakeGoogleOAuthPort(GoogleLoginSession session) {
			this.session = session;
		}

		@Override
		public GoogleLoginSession authenticate(GoogleAuthorizationApproval approval) {
			return session;
		}

		@Override
		public void revoke(GoogleLoginSession session) {
			revokedSession = session;
		}
	}
}
