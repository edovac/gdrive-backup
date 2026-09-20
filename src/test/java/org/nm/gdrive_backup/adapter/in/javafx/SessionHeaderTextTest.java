package org.nm.gdrive_backup.adapter.in.javafx;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
