package org.nm.gdrive_backup.domain.service;

import java.util.List;

import org.nm.gdrive_backup.domain.model.DownloadFailure;
import org.nm.gdrive_backup.domain.port.in.DownloadFailureReportUseCase;
import org.nm.gdrive_backup.domain.port.out.DownloadFailurePort;

public class DownloadFailureReportService implements DownloadFailureReportUseCase {

	private final DownloadFailurePort failurePort;

	public DownloadFailureReportService(DownloadFailurePort failurePort) {
		this.failurePort = failurePort;
	}

	@Override
	public List<DownloadFailure> openFailures() {
		return failurePort.findOpen();
	}
}
