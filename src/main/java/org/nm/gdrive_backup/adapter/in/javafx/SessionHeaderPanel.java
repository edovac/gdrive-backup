package org.nm.gdrive_backup.adapter.in.javafx;

import java.util.List;
import java.util.function.Consumer;

import org.nm.gdrive_backup.domain.model.WorkspaceUser;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * The header shared by every view once signed in: the Workspace user whose Drive is backed up and inspected, the
 * connection status and Sign out. It shows the selected Workspace user, not the admin's OAuth identity, which the
 * login session deliberately keeps opaque. Every view reads the one selection through {@link #selectedEmail}.
 */
public final class SessionHeaderPanel {

	private final Consumer<WorkspaceUser> onUserSelected;

	private final Label workingAs = new Label("Working as");
	private final ComboBox<WorkspaceUser> userPicker = new ComboBox<>();
	private final Label fixedUser = new Label();
	private final Label status = new Label();
	private final HBox root;

	private boolean silent;

	/**
	 * @param onUserSelected called on the FX thread when the admin picks a user; not called when the list is filled
	 *        or cleared by {@link #setUsers} and {@link #clearUsers}, so the owner reloads explicitly in those cases
	 * @param onSignOut called on the FX thread when the admin presses Sign out
	 */
	public SessionHeaderPanel(Consumer<WorkspaceUser> onUserSelected, Runnable onSignOut) {
		this.onUserSelected = onUserSelected;

		Label title = new Label("Google Drive Backup");
		title.getStyleClass().add("subtitle");
		workingAs.getStyleClass().add("scope");
		userPicker.setPromptText("Select Workspace user");
		userPicker.setCellFactory(view -> userCell());
		userPicker.setButtonCell(userCell());
		userPicker.setOnAction(event -> {
			WorkspaceUser selected = userPicker.getValue();
			if (!silent && selected != null) {
				this.onUserSelected.accept(selected);
			}
		});
		fixedUser.getStyleClass().add("status");
		status.getStyleClass().add("status");
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		Button signOut = new Button("Sign out");
		signOut.getStyleClass().add("secondary-button");
		signOut.setOnAction(event -> onSignOut.run());

		root = new HBox(12, title, workingAs, userPicker, fixedUser, status, spacer, signOut);
		root.setAlignment(Pos.CENTER_LEFT);
		root.setPadding(new Insets(8, 12, 8, 12));
		root.setStyle("-fx-background-color: white; -fx-border-color: #dadce0; -fx-border-width: 0 0 1 0;");
		clearUsers();
		hide();
	}

	public Node node() {
		return root;
	}

	public void show() {
		root.setVisible(true);
		root.setManaged(true);
	}

	public void hide() {
		root.setVisible(false);
		root.setManaged(false);
		status.setText("");
		clearUsers();
	}

	public void setStatus(String text) {
		status.setText(text);
	}

	/**
	 * Fills the picker and selects {@code preferredEmail} when it is listed, otherwise the first user. An empty list
	 * falls back to {@link #showFixedUser}.
	 */
	public void setUsers(List<WorkspaceUser> users, String preferredEmail) {
		if (users.isEmpty()) {
			showFixedUser(preferredEmail);
			return;
		}
		silent = true;
		try {
			userPicker.getItems().setAll(users);
			userPicker.setValue(users.stream()
					.filter(user -> user.email().equalsIgnoreCase(preferredEmail))
					.findFirst()
					.orElse(users.getFirst()));
		} finally {
			silent = false;
		}
		setPickerVisible(true);
	}

	/** The users cannot be listed: show the configured user, if any, as the only choice. */
	public void showFixedUser(String email) {
		clearPicker();
		fixedUser.setText(SessionHeaderText.fixedUser(email));
		setPickerVisible(false);
	}

	public void clearUsers() {
		clearPicker();
		fixedUser.setText("");
		setPickerVisible(false);
		fixedUser.setVisible(false);
		fixedUser.setManaged(false);
	}

	/** The selected user's email, or {@code fallbackEmail} when nobody is selected. */
	public String selectedEmail(String fallbackEmail) {
		WorkspaceUser selected = userPicker.getValue();
		return selected == null ? fallbackEmail : selected.email();
	}

	private void clearPicker() {
		silent = true;
		try {
			userPicker.getItems().clear();
			userPicker.setValue(null);
		} finally {
			silent = false;
		}
	}

	private void setPickerVisible(boolean picker) {
		userPicker.setVisible(picker);
		userPicker.setManaged(picker);
		fixedUser.setVisible(!picker);
		fixedUser.setManaged(!picker);
	}

	private static ListCell<WorkspaceUser> userCell() {
		return new ListCell<>() {
			@Override
			protected void updateItem(WorkspaceUser user, boolean empty) {
				super.updateItem(user, empty);
				setText(empty || user == null ? null : SessionHeaderText.user(user));
			}
		};
	}
}
