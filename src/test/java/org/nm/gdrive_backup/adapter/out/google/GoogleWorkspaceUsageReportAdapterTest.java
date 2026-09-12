package org.nm.gdrive_backup.adapter.out.google;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.api.services.reports.model.UsageReport;
import org.junit.jupiter.api.Test;

class GoogleWorkspaceUsageReportAdapterTest {

	@Test
	void convertsScalarReportParameterValues() {
		UsageReport.Parameters integer = new UsageReport.Parameters()
				.setName("accounts:total_users")
				.setIntValue(12L);
		UsageReport.Parameters text = new UsageReport.Parameters()
				.setName("accounts:edition")
				.setStringValue("Business");
		UsageReport.Parameters flag = new UsageReport.Parameters()
				.setName("accounts:enabled")
				.setBoolValue(true);

		assertEquals("12", GoogleWorkspaceUsageReportAdapter.scalarValue(integer));
		assertEquals("Business", GoogleWorkspaceUsageReportAdapter.scalarValue(text));
		assertEquals("true", GoogleWorkspaceUsageReportAdapter.scalarValue(flag));
		assertNull(GoogleWorkspaceUsageReportAdapter.scalarValue(new UsageReport.Parameters()));
	}
}
