package org.nm.gdrive_backup.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class BackupPropertiesTest {

	@Test
	void acceptsConcurrenciesFromOneToTheMaximum() {
		assertEquals(1, new BackupProperties(1).downloadConcurrency());
		assertEquals(BackupProperties.MAX_DOWNLOAD_CONCURRENCY,
				new BackupProperties(BackupProperties.MAX_DOWNLOAD_CONCURRENCY).downloadConcurrency());
	}

	@Test
	void rejectsConcurrenciesOutsideTheSupportedRange() {
		assertThrows(IllegalArgumentException.class, () -> new BackupProperties(0));
		assertThrows(IllegalArgumentException.class, () -> new BackupProperties(-3));
		assertThrows(IllegalArgumentException.class,
				() -> new BackupProperties(BackupProperties.MAX_DOWNLOAD_CONCURRENCY + 1));
	}

	@Test
	void defaultsToFourDownloadsAtOnceWhenNothingIsConfigured() {
		BackupProperties properties = bind(Map.of());

		assertEquals(4, properties.downloadConcurrency());
	}

	@Test
	void bindsTheDocumentedPropertyName() {
		BackupProperties properties = bind(Map.of("gdrive-backup.backup.download-concurrency", "8"));

		assertEquals(8, properties.downloadConcurrency());
	}

	private static BackupProperties bind(Map<String, String> source) {
		return new Binder(new MapConfigurationPropertySource(source))
				.bindOrCreate("gdrive-backup.backup", Bindable.of(BackupProperties.class));
	}
}
