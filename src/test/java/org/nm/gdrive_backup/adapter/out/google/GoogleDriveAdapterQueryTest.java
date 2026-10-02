package org.nm.gdrive_backup.adapter.out.google;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GoogleDriveAdapterQueryTest {

	@Test
	void leavesOrdinaryIdsAlone() {
		assertEquals("0AB-cd_123", GoogleDriveAdapter.quoted("0AB-cd_123"));
	}

	@Test
	void escapesQuotesAndBackslashesSoAnIdCannotEndTheQueryString() {
		assertEquals("a\\' or \\'1\\'=\\'1", GoogleDriveAdapter.quoted("a' or '1'='1"));
		assertEquals("a\\\\b", GoogleDriveAdapter.quoted("a\\b"));
	}
}
