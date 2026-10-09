package org.nm.gdrive_backup.adapter.in.javafx;

import java.util.List;
import java.util.function.Consumer;

import org.nm.gdrive_backup.domain.model.ApplicationInfo;
import org.nm.gdrive_backup.domain.model.DriveUserProfile;
import org.nm.gdrive_backup.domain.model.WorkspaceUser;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.ImagePattern;
import javafx.scene.shape.Circle;

/**
 * The header shared by every view once signed in: the app title and version, the signed-in admin's domain favicon
 * and avatar, the Workspace user whose Drive is backed up and inspected, the connection status and Sign out. The
 * admin avatar/name come from {@link #showAdmin} and {@link #showAdminProfile} (the Drive profile behind the OAuth
 * login, fetched separately); the Workspace user picker below it is a completely independent selection that the
 * login session keeps opaque. Every view reads that selection through {@link #selectedEmail}.
 */
public final class SessionHeaderPanel {

	private static final double AVATAR_SIZE = 28;
	private static final double FAVICON_SIZE = 16;

	private final Consumer<WorkspaceUser> onUserSelected;

	private final Label workingAs = new Label("Working as:");
	private final ComboBox<WorkspaceUser> userPicker = new ComboBox<>();
	private final Label fixedUser = new Label();
	private final Label status = new Label();
	private final VBox root;

	private final ImageView favicon = new ImageView();
	private final Label domainLabel = new Label();
	private final Circle avatarCircle = new Circle(AVATAR_SIZE / 2);
	private final Label avatarInitials = new Label();
	private final Label adminName = new Label();
	private final Tooltip adminTooltip = new Tooltip();
	private final HBox adminBox;

	private boolean silent;

	/**
	 * @param onUserSelected called on the FX thread when the admin picks a user; not called when the list is filled
	 *        or cleared by {@link #setUsers} and {@link #clearUsers}, so the owner reloads explicitly in those cases
	 * @param onSignOut called on the FX thread when the admin presses Sign out
	 * @param onSettings called on the FX thread when the admin presses Settings
	 */
	public SessionHeaderPanel(ApplicationInfo applicationInfo, Consumer<WorkspaceUser> onUserSelected,
			Runnable onSignOut, Runnable onSettings) {
		this.onUserSelected = onUserSelected;

		Label title = new Label("Google Drive Backup");
		title.getStyleClass().add("subtitle");
		title.setStyle("-fx-font-weight: bold;");
		Label version = new Label(ApplicationInfoText.versionLabel(applicationInfo));
		version.getStyleClass().add("scope");
		favicon.setFitWidth(FAVICON_SIZE);
		favicon.setFitHeight(FAVICON_SIZE);
		favicon.setVisible(false);
		favicon.setManaged(false);
		domainLabel.getStyleClass().add("scope");
		domainLabel.setVisible(false);
		domainLabel.setManaged(false);
		workingAs.getStyleClass().add("scope");
		userPicker.setPromptText("Select Workspace user");
		userPicker.setMaxWidth(Double.MAX_VALUE);
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

		avatarInitials.setStyle("-fx-text-fill: white; -fx-font-size: 11px; -fx-font-weight: bold;");
		StackPane avatar = new StackPane(avatarCircle, avatarInitials);
		avatar.setMinSize(AVATAR_SIZE, AVATAR_SIZE);
		avatar.setMaxSize(AVATAR_SIZE, AVATAR_SIZE);
		adminName.getStyleClass().add("status");
		adminBox = new HBox(6, avatar, adminName);
		adminBox.setAlignment(Pos.CENTER_LEFT);
		Tooltip.install(adminBox, adminTooltip);

		Button settings = new Button("Settings");
		settings.getStyleClass().add("secondary-button");
		settings.setOnAction(event -> onSettings.run());
		Button signOut = new Button("Sign out");
		signOut.getStyleClass().add("secondary-button");
		signOut.setOnAction(event -> onSignOut.run());
		// Only the title and the controls keep their full width. The other labels shorten with an ellipsis in a
		// narrow window: if they could not, the header's minimum width would exceed the window and the whole view,
		// tabs included, would be laid out that wide and cut off on the right.
		for (Label label : List.of(title, workingAs)) {
			label.setMinWidth(Region.USE_PREF_SIZE);
		}
		settings.setMinWidth(Region.USE_PREF_SIZE);
		signOut.setMinWidth(Region.USE_PREF_SIZE);
		userPicker.setMinWidth(0);

		// Two rows: identity and session on top, user below.
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox sessionRow = new HBox(8, favicon, title, version, domainLabel, spacer, status, adminBox, settings,
				signOut);
		sessionRow.setAlignment(Pos.CENTER_LEFT);
		HBox.setHgrow(userPicker, Priority.ALWAYS);
		HBox.setHgrow(fixedUser, Priority.ALWAYS);
		HBox userRow = new HBox(8, workingAs, userPicker, fixedUser);
		userRow.setAlignment(Pos.CENTER_LEFT);

		root = new VBox(8, sessionRow, userRow);
		root.setPadding(new Insets(10, 12, 10, 12));
		root.setStyle("-fx-background-color: white; -fx-border-color: #dadce0; -fx-border-width: 0 0 1 0;");
		clearUsers();
		clearAdmin();
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
		clearAdmin();
	}

	public void setStatus(String text) {
		status.setText(text);
	}

	/**
	 * Shows the admin's domain favicon and an initials avatar right after sign-in, before their Drive profile (name,
	 * photo) has loaded. {@link #showAdminProfile} replaces the name and, if one exists, the initials with a photo.
	 */
	public void showAdmin(String email) {
		String domain = SessionHeaderText.domainOf(email);
		setFavicon(SessionHeaderText.faviconUrl(domain));
		domainLabel.setText(domain == null ? "" : domain);
		setShown(domainLabel, domain != null);
		showInitials(email, null);
		adminName.setText(SessionHeaderText.adminName(email, null));
		adminTooltip.setText(email == null ? "" : email);
	}

	/** The admin's Drive profile arrived: refines the name and, if Drive reported a photo, the avatar. */
	public void showAdminProfile(DriveUserProfile profile) {
		if (profile == null) {
			return;
		}
		adminName.setText(SessionHeaderText.adminName(profile.email(), profile.displayName()));
		adminTooltip.setText(profile.email() == null ? "" : profile.email());
		showInitials(profile.email(), profile.displayName());
		if (profile.photoUrl() != null && !profile.photoUrl().isBlank()) {
			Image photo = new Image(profile.photoUrl(), AVATAR_SIZE * 2, AVATAR_SIZE * 2, true, true, true);
			// Background-loaded: progress reaches 1.0 (with no error) exactly once, on the FX thread, whether that
			// happens synchronously (already cached) or later; an error leaves the initials showing.
			photo.progressProperty().addListener((observable, was, progress) -> {
				if (progress.doubleValue() >= 1.0 && !photo.isError()) {
					avatarCircle.setFill(new ImagePattern(photo));
					avatarInitials.setVisible(false);
				}
			});
			if (photo.getProgress() >= 1.0 && !photo.isError()) {
				avatarCircle.setFill(new ImagePattern(photo));
				avatarInitials.setVisible(false);
			}
		}
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

	private void clearAdmin() {
		setFavicon(null);
		domainLabel.setText("");
		setShown(domainLabel, false);
		avatarCircle.setFill(Color.web(SessionHeaderText.avatarColor(null)));
		avatarInitials.setText("");
		avatarInitials.setVisible(true);
		adminName.setText("");
		adminTooltip.setText("");
	}

	private void showInitials(String email, String displayName) {
		avatarCircle.setFill(Color.web(SessionHeaderText.avatarColor(email)));
		avatarInitials.setText(SessionHeaderText.initials(email, displayName));
		avatarInitials.setVisible(true);
	}

	private void setFavicon(String url) {
		if (url == null) {
			favicon.setImage(null);
			setShown(favicon, false);
			return;
		}
		Image image = new Image(url, FAVICON_SIZE, FAVICON_SIZE, true, true, true);
		favicon.setImage(image);
		// Google's favicon service returns a generic globe rather than failing outright when a domain has none of
		// its own, so this only hides the icon on an outright load error (offline, blocked host, etc.).
		setShown(favicon, true);
		image.errorProperty().addListener((observable, was, failed) -> {
			if (failed && favicon.getImage() == image) {
				setShown(favicon, false);
			}
		});
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

	private static void setShown(Node node, boolean shown) {
		node.setVisible(shown);
		node.setManaged(shown);
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
