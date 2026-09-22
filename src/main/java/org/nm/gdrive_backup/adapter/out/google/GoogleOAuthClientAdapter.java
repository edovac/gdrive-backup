package org.nm.gdrive_backup.adapter.out.google;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.UrlEncodedContent;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.util.store.MemoryDataStoreFactory;
import org.nm.gdrive_backup.domain.model.GoogleLoginSession;
import org.nm.gdrive_backup.domain.port.in.GoogleAuthorizationApproval;
import org.nm.gdrive_backup.domain.port.out.GoogleOAuthPort;

import java.io.IOException;
import java.io.Reader;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GoogleOAuthClientAdapter implements GoogleOAuthPort {

	private static final String DRIVE_READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly";
	private static final String OPENID_SCOPE = "openid";
	private static final String EMAIL_SCOPE = "https://www.googleapis.com/auth/userinfo.email";
	private static final Set<String> LOGIN_SCOPES = Set.of(DRIVE_READONLY_SCOPE, OPENID_SCOPE, EMAIL_SCOPE);
	private static final String REVOCATION_URL = "https://oauth2.googleapis.com/revoke";
	private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

	private final HttpTransport httpTransport;
	private final GoogleClientSecrets clientSecrets;
	private final Map<UUID, Credential> credentials = new ConcurrentHashMap<>();

	public GoogleOAuthClientAdapter(HttpTransport httpTransport, Reader clientSecretsReader) throws IOException {
		this.httpTransport = httpTransport;
		this.clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, clientSecretsReader);
	}

	@Override
	public GoogleLoginSession authenticate(GoogleAuthorizationApproval approval) {
		LocalServerReceiver receiver = null;
		try {
			GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
					httpTransport,
					JSON_FACTORY,
					clientSecrets,
					LOGIN_SCOPES)
					.setAccessType("offline")
					.setDataStoreFactory(new MemoryDataStoreFactory())
					.build();
			receiver = new LocalServerReceiver.Builder().setPort(0).build();
			String userId = UUID.randomUUID().toString();
			String redirectUri = receiver.getRedirectUri();
			String authorizationUrl = flow.newAuthorizationUrl()
					.setResponseTypes(Set.of("code"))
					.setRedirectUri(redirectUri)
					.build();
			if (!approval.approve(java.net.URI.create(authorizationUrl))) {
				receiver.stop();
				throw new GoogleOAuthException("Google login cancelled");
			}
			String authorizationCode = receiver.waitForCode();
			GoogleTokenResponse tokenResponse = flow.newTokenRequest(authorizationCode)
					.setRedirectUri(redirectUri)
					.execute();
			Credential credential = flow.createAndStoreCredential(tokenResponse, userId);
			UUID sessionId = UUID.randomUUID();
			credentials.put(sessionId, credential);
			return new GoogleLoginSession(sessionId, expirationOf(credential), LOGIN_SCOPES,
					emailOf(tokenResponse));
		} catch (IOException exception) {
			throw new GoogleOAuthException("Google authentication failed", exception);
		} finally {
			if (receiver != null) {
				try {
					receiver.stop();
				} catch (IOException ignored) {
				}
			}
		}
	}

	@Override
	public void revoke(GoogleLoginSession session) {
		Credential credential = credentials.remove(session.sessionId());
		if (credential == null) {
			return;
		}
		String token = credential.getRefreshToken() != null
				? credential.getRefreshToken()
				: credential.getAccessToken();
		if (token == null) {
			return;
		}
		try {
			HttpRequestFactory requestFactory = httpTransport.createRequestFactory();
			HttpRequest request = requestFactory.buildPostRequest(
					new GenericUrl(REVOCATION_URL),
					new UrlEncodedContent(Map.of("token", token)));
			request.execute().disconnect();
		} catch (IOException exception) {
			throw new GoogleOAuthException("Google token revocation failed", exception);
		}
	}

	private static Instant expirationOf(Credential credential) {
		Long expiration = credential.getExpirationTimeMilliseconds();
		return expiration == null ? Instant.MAX : Instant.ofEpochMilli(expiration);
	}

	/**
	 * Reads the signed-in user's email from the ID token issued alongside the access token (the
	 * {@code openid}/{@code email} scopes). The ID token is parsed without signature verification:
	 * it arrives directly from Google's token endpoint over TLS in this back-channel exchange,
	 * never through a browser redirect, so it does not need the additional verification a
	 * redirect-delivered ID token would.
	 */
	private static String emailOf(GoogleTokenResponse tokenResponse) throws IOException {
		GoogleIdToken idToken = tokenResponse.parseIdToken();
		if (idToken == null) {
			throw new GoogleOAuthException("Google did not return an ID token; the email scope may be missing");
		}
		String email = idToken.getPayload().getEmail();
		if (email == null || email.isBlank()) {
			throw new GoogleOAuthException("Google did not return an email address for the signed-in user");
		}
		return email;
	}
}
