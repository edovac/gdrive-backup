package org.nm.gdrive_backup.adapter.out.google;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.reports.Reports;
import com.google.api.services.reports.model.UsageReport;
import com.google.api.services.reports.model.UsageReports;
import com.google.auth.http.HttpCredentialsAdapter;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.WorkspaceUsageMetric;
import org.nm.gdrive_backup.domain.model.WorkspaceUsageReport;
import org.nm.gdrive_backup.domain.port.out.WorkspaceUsageReportPort;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

public class GoogleWorkspaceUsageReportAdapter implements WorkspaceUsageReportPort {

	private static final String CUSTOMER_ID = "my_customer";
	private final GoogleServiceAccountAdapter credentialAdapter;

	public GoogleWorkspaceUsageReportAdapter(GoogleServiceAccountAdapter credentialAdapter) {
		this.credentialAdapter = credentialAdapter;
	}

	@Override
	public WorkspaceUsageReport getLatestReport(ServiceAccountAccess access) {
		LocalDate reportDate = LocalDate.now(ZoneOffset.UTC).minusDays(1);
		try {
			Reports reports = reports(access);
			List<WorkspaceUsageMetric> metrics = new ArrayList<>();
			String pageToken = null;
			do {
				Reports.CustomerUsageReports.Get request = reports.customerUsageReports()
						.get(reportDate.toString())
						.setCustomerId(CUSTOMER_ID)
						.setPageToken(pageToken)
						.setFields("usageReports(date,parameters(name,intValue,stringValue,boolValue)),nextPageToken");
				UsageReports response = request.execute();
				if (response != null && response.getUsageReports() != null) {
					for (UsageReport report : response.getUsageReports()) {
						if (report.getParameters() == null) {
							continue;
						}
						for (UsageReport.Parameters parameter : report.getParameters()) {
							String value = scalarValue(parameter);
							if (parameter.getName() != null && value != null) {
								metrics.add(new WorkspaceUsageMetric(parameter.getName(), value));
							}
						}
					}
				}
				pageToken = response == null ? null : response.getNextPageToken();
			} while (pageToken != null && !pageToken.isBlank());
			return new WorkspaceUsageReport(reportDate, metrics);
		} catch (IOException | GeneralSecurityException exception) {
			throw new GoogleDriveException("Unable to load Workspace usage report", exception);
		}
	}

	private Reports reports(ServiceAccountAccess access) throws IOException, GeneralSecurityException {
		if (access == null) {
			throw new IllegalArgumentException("access must not be null");
		}
		HttpRequestInitializer initializer = new HttpCredentialsAdapter(credentialAdapter.reportsCredentialsFor(access));
		return new Reports.Builder(
				GoogleNetHttpTransport.newTrustedTransport(),
				GsonFactory.getDefaultInstance(),
				initializer)
				.setApplicationName("gdrive-backup")
				.build();
	}

	static String scalarValue(UsageReport.Parameters parameter) {
		if (parameter == null) {
			return null;
		}
		if (parameter.getIntValue() != null) {
			return parameter.getIntValue().toString();
		}
		if (parameter.getStringValue() != null) {
			return parameter.getStringValue();
		}
		if (parameter.getBoolValue() != null) {
			return parameter.getBoolValue().toString();
		}
		return null;
	}
}
