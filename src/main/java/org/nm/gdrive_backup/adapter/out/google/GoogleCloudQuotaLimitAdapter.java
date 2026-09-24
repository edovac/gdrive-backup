package org.nm.gdrive_backup.adapter.out.google;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.serviceusage.v1.ServiceUsage;
import com.google.api.services.serviceusage.v1.model.GoogleApiServiceusageV1Service;
import com.google.api.services.serviceusage.v1.model.QuotaLimit;
import com.google.auth.http.HttpCredentialsAdapter;
import org.nm.gdrive_backup.domain.model.CloudQuotaLimit;
import org.nm.gdrive_backup.domain.port.out.CloudQuotaLimitPort;
import org.nm.gdrive_backup.domain.port.out.CredentialStoragePort;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

public class GoogleCloudQuotaLimitAdapter implements CloudQuotaLimitPort {

	private static final List<String> SERVICES = List.of(
			"drive.googleapis.com",
			"admin.googleapis.com");
	private static final String FIELDS = "name,config(title,quota(limits(metric,displayName,defaultLimit,maxLimit,unit)))";

	private final GoogleServiceAccountAdapter credentialAdapter;
	private final CredentialStoragePort credentialStoragePort;

	public GoogleCloudQuotaLimitAdapter(GoogleServiceAccountAdapter credentialAdapter,
			CredentialStoragePort credentialStoragePort) {
		this.credentialAdapter = credentialAdapter;
		this.credentialStoragePort = credentialStoragePort;
	}

	@Override
	public List<CloudQuotaLimit> listQuotaLimits() {
		String projectId = credentialStoragePort.projectId().orElse(null);
		if (projectId == null || projectId.isBlank()) {
			throw new GoogleOAuthException("Google Cloud project ID is not configured. Set it in Settings.");
		}
		try {
			ServiceUsage serviceUsage = serviceUsage();
			List<CloudQuotaLimit> limits = new ArrayList<>();
			for (String service : SERVICES) {
				GoogleApiServiceusageV1Service apiService = serviceUsage.services()
						.get("projects/" + projectId + "/services/" + service)
						.setFields(FIELDS)
						.execute();
				if (apiService == null || apiService.getConfig() == null || apiService.getConfig().getQuota() == null
						|| apiService.getConfig().getQuota().getLimits() == null) {
					continue;
				}
				for (QuotaLimit limit : apiService.getConfig().getQuota().getLimits()) {
					limits.add(new CloudQuotaLimit(
							service,
							limit.getMetric(),
							limit.getDisplayName(),
							limit.getDefaultLimit(),
							limit.getMaxLimit(),
							limit.getUnit()));
				}
			}
			return limits;
		} catch (IOException | GeneralSecurityException exception) {
			throw new GoogleDriveException("Unable to load Cloud API quota limits", exception);
		}
	}

	private ServiceUsage serviceUsage() throws IOException, GeneralSecurityException {
		HttpRequestInitializer initializer = new HttpCredentialsAdapter(credentialAdapter.cloudCredentials());
		return new ServiceUsage.Builder(
				GoogleNetHttpTransport.newTrustedTransport(),
				GsonFactory.getDefaultInstance(),
				initializer)
				.setApplicationName("gdrive-backup")
				.build();
	}
}
