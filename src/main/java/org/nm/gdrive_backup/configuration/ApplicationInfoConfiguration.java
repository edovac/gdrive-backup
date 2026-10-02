package org.nm.gdrive_backup.configuration;

import org.nm.gdrive_backup.domain.model.ApplicationInfo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApplicationInfoConfiguration {

	@Bean
	ApplicationInfo applicationInfo(ObjectProvider<BuildProperties> buildPropertiesProvider) {
		BuildProperties buildProperties = buildPropertiesProvider.getIfAvailable();
		if (buildProperties == null) {
			// An IDE/Maven run without a packaging step never generates build-info.properties.
			return ApplicationInfo.unknown();
		}
		return new ApplicationInfo(buildProperties.getVersion(), buildProperties.getTime());
	}
}
