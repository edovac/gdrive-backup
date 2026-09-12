package org.nm.gdrive_backup.adapter.out.google;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.About;
import com.google.auth.http.HttpCredentialsAdapter;
import org.nm.gdrive_backup.domain.model.DriveUsageQuota;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.port.out.DriveUsageQuotaPort;

import java.io.IOException;
import java.security.GeneralSecurityException;

public class GoogleDriveUsageQuotaAdapter implements DriveUsageQuotaPort {

	private static final String FIELDS = "user(emailAddress),storageQuota(limit,usage,usageInDrive,usageInDriveTrash)";

	private final GoogleServiceAccountAdapter credentialAdapter;

	public GoogleDriveUsageQuotaAdapter(GoogleServiceAccountAdapter credentialAdapter) {
		this.credentialAdapter = credentialAdapter;
	}

	@Override
	public DriveUsageQuota getUsageQuota(ServiceAccountAccess access) {
		try {
			About about = drive(access).about().get().setFields(FIELDS).execute();
			About.StorageQuota storageQuota = about == null ? null : about.getStorageQuota();
			String userEmail = about == null || about.getUser() == null ? access.impersonatedUserEmail()
					: about.getUser().getEmailAddress();
			return new DriveUsageQuota(
					userEmail == null || userEmail.isBlank() ? access.impersonatedUserEmail() : userEmail,
					storageQuota == null ? null : storageQuota.getUsage(),
					storageQuota == null ? null : storageQuota.getLimit(),
					storageQuota == null ? null : storageQuota.getUsageInDrive(),
					storageQuota == null ? null : storageQuota.getUsageInDriveTrash());
		} catch (IOException | GeneralSecurityException exception) {
			throw new GoogleDriveException("Unable to load Drive usage quota", exception);
		}
	}

	private Drive drive(ServiceAccountAccess access) throws IOException, GeneralSecurityException {
		if (access == null) {
			throw new IllegalArgumentException("access must not be null");
		}
		HttpRequestInitializer initializer = new HttpCredentialsAdapter(credentialAdapter.credentialsFor(access));
		return new Drive.Builder(
				GoogleNetHttpTransport.newTrustedTransport(),
				GsonFactory.getDefaultInstance(),
				initializer)
				.setApplicationName("gdrive-backup")
				.build();
	}
}
