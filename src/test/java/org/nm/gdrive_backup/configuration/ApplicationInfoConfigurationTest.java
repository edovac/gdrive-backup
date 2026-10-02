package org.nm.gdrive_backup.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.ApplicationInfo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

class ApplicationInfoConfigurationTest {

	private final ApplicationInfoConfiguration configuration = new ApplicationInfoConfiguration();

	@Test
	void usesTheProjectVersionAndBuildTime() {
		// ProjectInfoAutoConfiguration strips the "build." prefix from build-info.properties before constructing
		// BuildProperties, so the properties it receives (and the ones built here) already have plain keys.
		Properties properties = new Properties();
		properties.setProperty("version", "0.1.0");
		properties.setProperty("time", "2026-09-20T08:30:05.000Z");
		BuildProperties buildProperties = new BuildProperties(properties);

		ApplicationInfo info = configuration.applicationInfo(providerOf(buildProperties));

		assertEquals("0.1.0", info.version());
		assertEquals(Instant.parse("2026-09-20T08:30:05Z"), info.buildTime());
	}

	@Test
	void isUnknownWithoutBuildInfo() {
		ApplicationInfo info = configuration.applicationInfo(providerOf(null));

		assertNull(info.version());
		assertNull(info.buildTime());
	}

	@SuppressWarnings("unchecked")
	private static ObjectProvider<BuildProperties> providerOf(BuildProperties buildProperties) {
		ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(buildProperties);
		return provider;
	}
}
