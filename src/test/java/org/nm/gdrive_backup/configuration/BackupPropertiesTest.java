package org.nm.gdrive_backup.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.PersonalDriveContent;
import org.nm.gdrive_backup.domain.service.DownloadConcurrencyService;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class BackupPropertiesTest {

	@Test
	void acceptsConcurrenciesFromOneToTheMaximum() {
		assertEquals(1, new BackupProperties(1, PersonalDriveContent.OWNED_ONLY).downloadConcurrency());
		assertEquals(DownloadConcurrencyService.MAXIMUM,
				new BackupProperties(DownloadConcurrencyService.MAXIMUM, PersonalDriveContent.OWNED_ONLY)
						.downloadConcurrency());
	}

	@Test
	void rejectsConcurrenciesOutsideTheSupportedRange() {
		assertThrows(IllegalArgumentException.class, () -> new BackupProperties(0, PersonalDriveContent.OWNED_ONLY));
		assertThrows(IllegalArgumentException.class, () -> new BackupProperties(-3, PersonalDriveContent.OWNED_ONLY));
		assertThrows(IllegalArgumentException.class,
				() -> new BackupProperties(DownloadConcurrencyService.MAXIMUM + 1, PersonalDriveContent.OWNED_ONLY));
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

	@Test
	void personalDrivesTakeOnlyOwnedFilesWhenNothingIsConfigured() {
		assertEquals(PersonalDriveContent.OWNED_ONLY, bind(Map.of()).personalDriveContent());
	}

	@Test
	void bindsThePersonalDriveContentProperty() {
		BackupProperties properties = bind(Map.of("gdrive-backup.backup.personal-drive-content", "all-accessible"));

		assertEquals(PersonalDriveContent.ALL_ACCESSIBLE, properties.personalDriveContent());
	}

	private static BackupProperties bind(Map<String, String> source) {
		return new Binder(new MapConfigurationPropertySource(source))
				.bindOrCreate("gdrive-backup.backup", Bindable.of(BackupProperties.class));
	}
}
