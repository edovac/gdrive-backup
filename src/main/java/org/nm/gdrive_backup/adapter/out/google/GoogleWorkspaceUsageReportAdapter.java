package org.nm.gdrive_backup.adapter.out.google;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.reports.Reports;
import com.google.api.services.reports.model.UsageReport;
import com.google.api.services.reports.model.UsageReports;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.auth.http.HttpCredentialsAdapter;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.WorkspaceUsageMetric;
import org.nm.gdrive_backup.domain.model.WorkspaceUsageReport;
import org.nm.gdrive_backup.domain.port.out.WorkspaceUsageReportPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

public class GoogleWorkspaceUsageReportAdapter implements WorkspaceUsageReportPort {

	private static final Logger LOGGER = LoggerFactory.getLogger(GoogleWorkspaceUsageReportAdapter.class);
	private static final String CUSTOMER_ID = "my_customer";
	private static final String PARAMETERS = String.join(",",
			"accounts:num_users",
			"accounts:drive_used_quota_in_mb",
			"accounts:customer_used_quota_in_mb",
			"accounts:total_quota_in_mb",
			"accounts:num_users_used_quota_ge_80percent");
	private static final int MAX_REPORT_LOOKBACK_DAYS = 14;
	private final GoogleServiceAccountAdapter credentialAdapter;

	public GoogleWorkspaceUsageReportAdapter(GoogleServiceAccountAdapter credentialAdapter) {
		this.credentialAdapter = credentialAdapter;
	}

	@Override
	public WorkspaceUsageReport getLatestReport(ServiceAccountAccess access) {
		LocalDate requestedDate = LocalDate.now(ZoneOffset.UTC).minusDays(1);
		LOGGER.info("Starting Workspace usage report request: date={}, customerId={}, user={}, scopes={}",
				requestedDate, CUSTOMER_ID, access == null ? "<null>" : access.impersonatedUserEmail(),
				access == null ? "<null>" : access.scopes());
		try {
			Reports reports = reports(access);
			for (int daysBack = 0; daysBack <= MAX_REPORT_LOOKBACK_DAYS; daysBack++) {
				LocalDate reportDate = requestedDate.minusDays(daysBack);
				try {
					List<WorkspaceUsageMetric> metrics = loadReportForDate(reports, reportDate);
					LOGGER.info("Workspace usage report completed: requestedDate={}, reportDate={}, metricCount={}",
							requestedDate, reportDate, metrics.size());
					return new WorkspaceUsageReport(reportDate, metrics);
				} catch (GoogleJsonResponseException exception) {
					String reason = exception.getDetails() == null ? exception.getStatusMessage()
							: exception.getDetails().getMessage();
					if (isReportNotReady(exception, reason) && daysBack < MAX_REPORT_LOOKBACK_DAYS) {
						LOGGER.warn("Workspace usage data is not ready for {}; trying {}", reportDate,
								requestedDate.minusDays(daysBack + 1));
						continue;
					}
					throw exception;
				}
			}
			throw new GoogleDriveException("No Workspace usage report is available", null);
		} catch (GoogleJsonResponseException exception) {
			String reason = exception.getDetails() == null ? exception.getStatusMessage()
					: exception.getDetails().getMessage();
			if (isReportingAccessDenied(exception.getStatusCode(), reason)) {
				LOGGER.warn("Workspace usage report unavailable: delegated account is not authorized for Reports data");
				throw new GoogleDriveException(
						"Workspace Reports access is not authorized. Add the Reports read-only scope "
								+ "and use an administrator account.", exception);
			}
			LOGGER.error("Workspace usage report failed: status={}, reason={}, details={}",
					exception.getStatusCode(), reason, exception.getDetails(), exception);
			throw new GoogleDriveException(
					"Unable to load Workspace usage report (HTTP " + exception.getStatusCode() + "): " + reason,
					exception);
		} catch (IOException | GeneralSecurityException exception) {
			LOGGER.error("Workspace usage report failed before receiving a Google response", exception);
			throw new GoogleDriveException("Unable to load Workspace usage report", exception);
		}
	}

	static boolean isReportingAccessDenied(int statusCode, String reason) {
		return statusCode == 403 && reason != null
				&& reason.toLowerCase(java.util.Locale.ROOT).contains("caller does not have access");
	}

	private List<WorkspaceUsageMetric> loadReportForDate(Reports reports, LocalDate reportDate) throws IOException {
		List<WorkspaceUsageMetric> metrics = new ArrayList<>();
		String pageToken = null;
		do {
			Reports.CustomerUsageReports.Get request = reports.customerUsageReports()
					.get(reportDate.toString())
					.setCustomerId(CUSTOMER_ID)
					.setParameters(PARAMETERS)
					.setPageToken(pageToken)
					.setFields("usageReports(date,parameters(name,intValue,stringValue,boolValue)),nextPageToken");
			LOGGER.info("Workspace usage report HTTP request: url={}, pageTokenPresent={}, parameters={}",
					request.buildHttpRequest().getUrl(), pageToken != null && !pageToken.isBlank(), PARAMETERS);
			UsageReports response = request.execute();
			LOGGER.info("Workspace usage report HTTP response: date={}, reportCount={}, nextPageTokenPresent={}",
					reportDate,
					response == null || response.getUsageReports() == null ? 0 : response.getUsageReports().size(),
					response != null && response.getNextPageToken() != null
							&& !response.getNextPageToken().isBlank());
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
		return metrics;
	}

	private static boolean isReportNotReady(GoogleJsonResponseException exception, String reason) {
		return exception.getStatusCode() == 400 && reason != null
				&& reason.contains("Data for dates later than");
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
