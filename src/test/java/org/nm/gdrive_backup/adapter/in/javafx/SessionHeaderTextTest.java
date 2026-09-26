package org.nm.gdrive_backup.adapter.in.javafx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.nm.gdrive_backup.domain.model.WorkspaceUser;

class SessionHeaderTextTest {

	@Test
	void showsTheDisplayNameWithTheEmailOrJustTheEmail() {
		assertEquals("Ada Lovelace (ada@example.com)",
				SessionHeaderText.user(new WorkspaceUser("ada@example.com", "Ada Lovelace")));
		assertEquals("ada@example.com", SessionHeaderText.user(new WorkspaceUser("ada@example.com", null)));
	}

	@Test
	void namesTheFixedUserOrSaysNoneIsSelected() {
		assertEquals("admin@example.com", SessionHeaderText.fixedUser("admin@example.com"));
		assertEquals("No user selected", SessionHeaderText.fixedUser(null));
		assertEquals("No user selected", SessionHeaderText.fixedUser(" "));
	}

	@Test
	void extractsTheDomainOrNoneWithoutOne() {
		assertEquals("acme.com", SessionHeaderText.domainOf("admin@Acme.com"));
		assertNull(SessionHeaderText.domainOf("not-an-email"));
		assertNull(SessionHeaderText.domainOf(null));
		assertNull(SessionHeaderText.domainOf("admin@"));
	}

	@Test
	void buildsTheFaviconUrlFromTheDomainOrNothingWithoutOne() {
		assertEquals("https://www.google.com/s2/favicons?sz=64&domain=acme.com", SessionHeaderText.faviconUrl("acme.com"));
		assertNull(SessionHeaderText.faviconUrl(null));
		assertNull(SessionHeaderText.faviconUrl(""));
	}

	@Test
	void picksTheDisplayNameOrTheEmailForTheAdminName() {
		assertEquals("Ada Lovelace", SessionHeaderText.adminName("ada@example.com", "Ada Lovelace"));
		assertEquals("ada@example.com", SessionHeaderText.adminName("ada@example.com", null));
		assertEquals("ada@example.com", SessionHeaderText.adminName("ada@example.com", " "));
	}

	@Test
	void buildsInitialsFromTheDisplayNameOrTheEmailsFirstLetter() {
		assertEquals("AL", SessionHeaderText.initials("ada@example.com", "Ada Lovelace"));
		assertEquals("A", SessionHeaderText.initials("ada@example.com", "Ada"));
		assertEquals("A", SessionHeaderText.initials("ada@example.com", null));
		assertEquals("?", SessionHeaderText.initials(null, null));
	}

	@Test
	void picksAStableAvatarColorForTheSameEmail() {
		String first = SessionHeaderText.avatarColor("ada@example.com");

		assertEquals(first, SessionHeaderText.avatarColor("ada@example.com"));
		assertEquals(SessionHeaderText.avatarColor(null), SessionHeaderText.avatarColor(""));
	}
}
