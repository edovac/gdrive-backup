package org.nm.gdrive_backup;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.nm.gdrive_backup.configuration.DrivePreviewProperties;
import org.nm.gdrive_backup.domain.port.in.ServiceAccountAuthenticationUseCase;
import org.nm.gdrive_backup.domain.port.in.DriveUsageQuotaUseCase;
import org.nm.gdrive_backup.domain.port.in.WorkspaceUserListingUseCase;
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
		JavaFxApplication.setDriveServices(
				springContext.getBeanProvider(ServiceAccountAuthenticationUseCase.class).getIfAvailable(),
				springContext.getBeanProvider(DriveReadPort.class).getIfAvailable(),
				springContext.getBeanProvider(WorkspaceUserListingUseCase.class).getIfAvailable(),
				 springContext.getBeanProvider(DriveUsageQuotaUseCase.class).getIfAvailable(),
				springContext.getBean(DrivePreviewProperties.class).userEmail());
		JavaFxApplication.launch(JavaFxApplication.class, args);
	}

}
