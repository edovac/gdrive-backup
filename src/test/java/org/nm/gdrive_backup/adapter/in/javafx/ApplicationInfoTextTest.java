package org.nm.gdrive_backup.adapter.in.javafx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.ApplicationInfo;

class ApplicationInfoTextTest {

	@Test
	void showsTheKnownVersionOrDevelopmentBuild() {
		assertEquals("v0.1.0", ApplicationInfoText.versionLabel(new ApplicationInfo("0.1.0", null)));
		assertEquals("development build", ApplicationInfoText.versionLabel(ApplicationInfo.unknown()));
		assertEquals("development build", ApplicationInfoText.versionLabel(new ApplicationInfo(" ", null)));
	}

	@Test
	void statusNamesTheAppAndVersion() {
		assertEquals("Google Drive Backup v0.1.0", ApplicationInfoText.status(new ApplicationInfo("0.1.0", null)));
	}

	@Test
	void listsTheBuildTimeInTheGivenZoneOrSaysItIsUnknown() {
		ApplicationInfo info = new ApplicationInfo("0.1.0", Instant.parse("2026-09-20T08:30:05Z"));

		assertEquals(List.of("Built: 2026-09-20 10:30:05"),
				ApplicationInfoText.lines(info, ZoneId.of("Europe/Rome")));
		assertEquals(List.of("Build time: unknown"),
				ApplicationInfoText.lines(ApplicationInfo.unknown(), ZoneId.of("Europe/Rome")));
	}
}
