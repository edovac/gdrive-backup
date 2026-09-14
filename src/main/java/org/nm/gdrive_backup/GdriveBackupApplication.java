package org.nm.gdrive_backup;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.nm.gdrive_backup.configuration.DrivePreviewProperties;
import org.nm.gdrive_backup.domain.port.in.ServiceAccountAuthenticationUseCase;
import org.nm.gdrive_backup.domain.port.in.DriveUsageQuotaUseCase;
import org.nm.gdrive_backup.domain.port.in.WorkspaceUsageReportUseCase;
import org.nm.gdrive_backup.domain.port.in.CloudQuotaLimitUseCase;
import org.nm.gdrive_backup.domain.port.in.WorkspaceUserListingUseCase;
import org.nm.gdrive_backup.domain.port.in.DriveBackupUseCase;
import org.nm.gdrive_backup.domain.port.in.BackupCancellationUseCase;
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
		JavaFxApplication.setLoginUseCase(springContext.getBean(org.nm.gdrive_backup.domain.port.in.GoogleLoginUseCase.class));
		JavaFxApplication.setBackupLocationUseCase(springContext
				.getBeanProvider(org.nm.gdrive_backup.domain.port.in.BackupLocationUseCase.class).getIfAvailable());
		JavaFxApplication.setBackupProgress(springContext.getBeanProvider(BackupProgressPort.class).getIfAvailable());
		JavaFxApplication.setBackupCancellation(
				springContext.getBeanProvider(BackupCancellationUseCase.class).getIfAvailable());
		JavaFxApplication.setDriveServices(
				springContext.getBeanProvider(ServiceAccountAuthenticationUseCase.class).getIfAvailable(),
				driveReadPort(springContext),
				springContext.getBeanProvider(WorkspaceUserListingUseCase.class).getIfAvailable(),
				 springContext.getBeanProvider(DriveUsageQuotaUseCase.class).getIfAvailable(),
				 springContext.getBeanProvider(WorkspaceUsageReportUseCase.class).getIfAvailable(),
				 springContext.getBeanProvider(CloudQuotaLimitUseCase.class).getIfAvailable(),
				 springContext.getBeanProvider(DriveBackupUseCase.class).getIfAvailable(),
				springContext.getBean(DrivePreviewProperties.class).userEmail());
		JavaFxApplication.launch(JavaFxApplication.class, args);
	}

	private static DriveReadPort driveReadPort(ConfigurableApplicationContext springContext) {
		return springContext.containsBean("driveReadPort")
				? springContext.getBean("driveReadPort", DriveReadPort.class)
				: null;
	}

}
