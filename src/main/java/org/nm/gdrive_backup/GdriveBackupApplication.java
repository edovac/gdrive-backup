package org.nm.gdrive_backup;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.nm.gdrive_backup.domain.port.in.ServiceAccountAuthenticationUseCase;
import org.nm.gdrive_backup.domain.port.in.DriveUsageQuotaUseCase;
import org.nm.gdrive_backup.domain.port.in.WorkspaceUsageReportUseCase;
import org.nm.gdrive_backup.domain.port.in.CloudQuotaLimitUseCase;
import org.nm.gdrive_backup.domain.port.in.WorkspaceUserListingUseCase;
import org.nm.gdrive_backup.domain.port.in.DriveBackupUseCase;
import org.nm.gdrive_backup.domain.port.in.BackupCancellationUseCase;
import org.nm.gdrive_backup.domain.port.in.DriveUserProfileUseCase;
import org.nm.gdrive_backup.domain.port.in.DownloadConcurrencyUseCase;
import org.nm.gdrive_backup.domain.port.in.PersonalDriveContentUseCase;
import org.nm.gdrive_backup.domain.model.ApplicationInfo;
import org.nm.gdrive_backup.domain.port.out.BackupProgressPort;
import org.nm.gdrive_backup.domain.port.out.DriveReadPort;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GdriveBackupApplication {

	public static void main(String[] args) {
		ConfigurableApplicationContext springContext = new SpringApplicationBuilder(GdriveBackupApplication.class)
				.headless(false)
				.run(args);
		JavaFxApplication.setSpringContext(springContext);
		JavaFxApplication.setApplicationInfo(springContext.getBean(ApplicationInfo.class));
		JavaFxApplication.setLoginUseCase(springContext.getBean(org.nm.gdrive_backup.domain.port.in.GoogleLoginUseCase.class));
		JavaFxApplication.setBackupLocationUseCase(springContext
				.getBeanProvider(org.nm.gdrive_backup.domain.port.in.BackupLocationUseCase.class).getIfAvailable());
		JavaFxApplication.setCredentialConfigurationUseCase(springContext
				.getBeanProvider(org.nm.gdrive_backup.domain.port.in.CredentialConfigurationUseCase.class).getIfAvailable());
		JavaFxApplication.setDownloadConcurrencyUseCase(
				springContext.getBeanProvider(DownloadConcurrencyUseCase.class).getIfAvailable());
		JavaFxApplication.setPersonalDriveContentUseCase(
				springContext.getBeanProvider(PersonalDriveContentUseCase.class).getIfAvailable());
		JavaFxApplication.setBackupProgress(springContext.getBeanProvider(BackupProgressPort.class).getIfAvailable());
		JavaFxApplication.setBackupCancellation(
				springContext.getBeanProvider(BackupCancellationUseCase.class).getIfAvailable());
		JavaFxApplication.setHistoryService(springContext
				.getBeanProvider(org.nm.gdrive_backup.domain.port.in.FileHistoryUseCase.class).getIfAvailable());
		JavaFxApplication.setDownloadFailureReport(springContext
				.getBeanProvider(org.nm.gdrive_backup.domain.port.in.DownloadFailureReportUseCase.class).getIfAvailable());
		JavaFxApplication.setArchiveServices(
				springContext.getBeanProvider(org.nm.gdrive_backup.domain.port.in.ArchiveCatalogUseCase.class).getIfAvailable(),
				springContext.getBeanProvider(org.nm.gdrive_backup.domain.port.in.ArchiveMergeUseCase.class).getIfAvailable(),
				springContext.getBeanProvider(org.nm.gdrive_backup.domain.port.in.ArchiveDeletionUseCase.class).getIfAvailable(),
				springContext.getBeanProvider(org.nm.gdrive_backup.domain.port.in.DatabaseRebuildUseCase.class).getIfAvailable());
		JavaFxApplication.setDriveServices(
				springContext.getBeanProvider(ServiceAccountAuthenticationUseCase.class).getIfAvailable(),
				driveReadPort(springContext),
				springContext.getBeanProvider(WorkspaceUserListingUseCase.class).getIfAvailable(),
				 springContext.getBeanProvider(DriveUsageQuotaUseCase.class).getIfAvailable(),
				 springContext.getBeanProvider(WorkspaceUsageReportUseCase.class).getIfAvailable(),
				 springContext.getBeanProvider(CloudQuotaLimitUseCase.class).getIfAvailable(),
				 springContext.getBeanProvider(DriveBackupUseCase.class).getIfAvailable(),
				 springContext.getBeanProvider(DriveUserProfileUseCase.class).getIfAvailable());
		JavaFxApplication.launch(JavaFxApplication.class, args);
	}

	private static DriveReadPort driveReadPort(ConfigurableApplicationContext springContext) {
		return springContext.containsBean("driveReadPort")
				? springContext.getBean("driveReadPort", DriveReadPort.class)
				: null;
	}

}
