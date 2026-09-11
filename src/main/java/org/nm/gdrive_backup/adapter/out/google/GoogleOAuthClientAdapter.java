package org.nm.gdrive_backup.adapter.out.google;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.UrlEncodedContent;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.util.store.MemoryDataStoreFactory;
import org.nm.gdrive_backup.domain.model.GoogleLoginSession;
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
	public GoogleLoginSession authenticate() {
		try {
			GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
					httpTransport,
					JSON_FACTORY,
					clientSecrets,
					Set.of(DRIVE_READONLY_SCOPE))
					.setAccessType("offline")
					.setDataStoreFactory(new MemoryDataStoreFactory())
					.build();
			LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(0).build();
			Credential credential = new AuthorizationCodeInstalledApp(flow, receiver)
					.authorize(UUID.randomUUID().toString());
			UUID sessionId = UUID.randomUUID();
			credentials.put(sessionId, credential);
			return new GoogleLoginSession(sessionId, expirationOf(credential), Set.of(DRIVE_READONLY_SCOPE));
		} catch (IOException exception) {
			throw new GoogleOAuthException("Google authentication failed", exception);
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
}
