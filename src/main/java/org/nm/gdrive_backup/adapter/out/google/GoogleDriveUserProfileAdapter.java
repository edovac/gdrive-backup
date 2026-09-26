package org.nm.gdrive_backup.adapter.out.google;

import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.About;
import com.google.api.services.drive.model.User;
import com.google.auth.http.HttpCredentialsAdapter;
import org.nm.gdrive_backup.domain.model.DriveUserProfile;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.port.out.DriveUserProfilePort;

import java.io.IOException;

/**
 * Reads the impersonated admin's own Drive profile (name, photo) through the service account, the same way
 * {@link GoogleDriveUsageQuotaAdapter} reads their storage quota — no extra OAuth scope, since domain-wide
 * delegation already grants {@code drive.readonly} for this user.
 */
public class GoogleDriveUserProfileAdapter implements DriveUserProfilePort {

	private static final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();
	private static final String FIELDS = "user(displayName,emailAddress,photoLink)";

	private final GoogleServiceAccountAdapter credentialAdapter;

	public GoogleDriveUserProfileAdapter(GoogleServiceAccountAdapter credentialAdapter) {
		this.credentialAdapter = credentialAdapter;
	}

	@Override
	public DriveUserProfile getProfile(ServiceAccountAccess access) {
		try {
			About about = drive(access).about().get().setFields(FIELDS).execute();
			User user = about == null ? null : about.getUser();
			String email = user == null || user.getEmailAddress() == null || user.getEmailAddress().isBlank()
					? access.impersonatedUserEmail() : user.getEmailAddress();
			return new DriveUserProfile(email, user == null ? null : user.getDisplayName(),
					user == null ? null : user.getPhotoLink());
		} catch (IOException exception) {
			throw new GoogleDriveException("Unable to load Drive user profile", exception);
		}
	}

	private Drive drive(ServiceAccountAccess access) throws IOException {
		if (access == null) {
			throw new IllegalArgumentException("access must not be null");
		}
		HttpRequestInitializer initializer = new HttpCredentialsAdapter(credentialAdapter.credentialsFor(access));
		return new Drive.Builder(
				HTTP_TRANSPORT,
				GsonFactory.getDefaultInstance(),
				initializer)
				.setApplicationName("gdrive-backup")
				.build();
	}
}
