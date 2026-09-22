package org.nm.gdrive_backup;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.util.StringConverter;
import org.springframework.context.ConfigurableApplicationContext;
import org.nm.gdrive_backup.domain.model.GoogleLoginSession;
import org.nm.gdrive_backup.domain.port.in.GoogleLoginUseCase;
import org.nm.gdrive_backup.domain.port.in.DriveUsageQuotaUseCase;
import org.nm.gdrive_backup.domain.port.in.WorkspaceUsageReportUseCase;
import org.nm.gdrive_backup.domain.port.in.CloudQuotaLimitUseCase;
import org.nm.gdrive_backup.domain.port.in.ServiceAccountAuthenticationUseCase;
import org.nm.gdrive_backup.domain.port.in.WorkspaceUserListingUseCase;
import org.nm.gdrive_backup.domain.port.in.DriveBackupUseCase;
import org.nm.gdrive_backup.adapter.in.javafx.ArchiveManagerPanel;
import org.nm.gdrive_backup.adapter.in.javafx.BackupSummaryText;
import org.nm.gdrive_backup.adapter.in.javafx.FileHistoryPanel;
import org.nm.gdrive_backup.adapter.in.javafx.OperationProgressPanel;
import org.nm.gdrive_backup.adapter.in.javafx.SessionHeaderPanel;
import org.nm.gdrive_backup.adapter.in.javafx.TechnicalInfoPanel;
import org.nm.gdrive_backup.domain.port.in.ArchiveCatalogUseCase;
import org.nm.gdrive_backup.domain.port.in.ArchiveDeletionUseCase;
import org.nm.gdrive_backup.domain.port.in.ArchiveMergeUseCase;
import org.nm.gdrive_backup.domain.port.in.BackupCancellationUseCase;
import org.nm.gdrive_backup.domain.port.out.BackupProgressPort;
import org.nm.gdrive_backup.domain.port.out.DriveReadPort;
import org.nm.gdrive_backup.domain.model.AvailableDrive;
import org.nm.gdrive_backup.domain.model.DriveItem;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.BackupResult;
import org.nm.gdrive_backup.domain.model.BackupMode;
import org.nm.gdrive_backup.domain.model.BackupLocation;
import org.nm.gdrive_backup.domain.model.LocationStatus;
import org.nm.gdrive_backup.domain.model.LocationValidation;
import org.nm.gdrive_backup.domain.port.in.BackupLocationUseCase;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class JavaFxApplication extends Application {

	private static ConfigurableApplicationContext springContext;
	private static GoogleLoginUseCase loginUseCase;
	private static ServiceAccountAuthenticationUseCase serviceAccountUseCase;
	private static WorkspaceUserListingUseCase workspaceUserListingUseCase;
	private static DriveUsageQuotaUseCase driveUsageQuotaUseCase;
	private static WorkspaceUsageReportUseCase workspaceUsageReportUseCase;
	private static CloudQuotaLimitUseCase cloudQuotaLimitUseCase;
	private static DriveReadPort driveReadPort;
	private static DriveBackupUseCase driveBackupUseCase;
	private static String previewUserEmail;
	private static BackupLocationUseCase backupLocationUseCase;
	private static BackupProgressPort backupProgressPort;
	private static BackupCancellationUseCase backupCancellationUseCase;
	private static ArchiveCatalogUseCase archiveCatalogUseCase;
	private static ArchiveMergeUseCase archiveMergeUseCase;
	private static org.nm.gdrive_backup.domain.port.in.FileHistoryUseCase fileHistoryUseCase;
	private static ArchiveDeletionUseCase archiveDeletionUseCase;

	private final Button signIn = new Button("Sign in with Google");
	private final Label connectionStatus = new Label();
	private final Label driveStatus = new Label();
	private final Label drivesLabel = new Label("Select the drive(s) to back up:");
	private final Map<AvailableDrive, BooleanProperty> driveSelections = new HashMap<>();
	private final ListView<AvailableDrive> drives = new ListView<>();
	private final ListView<DriveItem> driveItems = new ListView<>();
	private final ComboBox<BackupMode> backupModeCombo = new ComboBox<>();
	private final Button syncNow = new Button("Sync selected drives");
	private final TabPane tabs = new TabPane();

	private BorderPane root;
	private Node loginView;
	private GoogleLoginSession session;
	private SessionHeaderPanel header;
	private TechnicalInfoPanel technicalInfoPanel;
	private LocationsPanel locationsPanel;
	private OperationProgressPanel progressPanel;
	private OperationProgressPanel archiveProgressPanel;
	private ArchiveManagerPanel archiveManagerPanel;
	private FileHistoryPanel fileHistoryPanel;

	static void setSpringContext(ConfigurableApplicationContext context) {
		springContext = context;
	}

	static void setLoginUseCase(GoogleLoginUseCase useCase) {
		loginUseCase = useCase;
	}

	static void setBackupLocationUseCase(BackupLocationUseCase useCase) {
		backupLocationUseCase = useCase;
	}

	static void setBackupProgress(BackupProgressPort progressPort) {
		backupProgressPort = progressPort;
	}

	static void setBackupCancellation(BackupCancellationUseCase cancellationUseCase) {
		backupCancellationUseCase = cancellationUseCase;
	}

	static void setHistoryService(org.nm.gdrive_backup.domain.port.in.FileHistoryUseCase historyUseCase) {
		fileHistoryUseCase = historyUseCase;
	}

	static void setArchiveServices(ArchiveCatalogUseCase catalogUseCase, ArchiveMergeUseCase mergeUseCase,
			ArchiveDeletionUseCase deletionUseCase) {
		archiveCatalogUseCase = catalogUseCase;
		archiveMergeUseCase = mergeUseCase;
		archiveDeletionUseCase = deletionUseCase;
	}

	static void setDriveServices(ServiceAccountAuthenticationUseCase authenticationUseCase,
			DriveReadPort readPort, WorkspaceUserListingUseCase workspaceUserUseCase,
			DriveUsageQuotaUseCase usageQuotaUseCase, WorkspaceUsageReportUseCase usageReportUseCase,
			CloudQuotaLimitUseCase cloudQuotaUseCase, DriveBackupUseCase backupUseCase) {
		serviceAccountUseCase = authenticationUseCase;
		driveReadPort = readPort;
		workspaceUserListingUseCase = workspaceUserUseCase;
		driveUsageQuotaUseCase = usageQuotaUseCase;
		workspaceUsageReportUseCase = usageReportUseCase;
		cloudQuotaLimitUseCase = cloudQuotaUseCase;
		driveBackupUseCase = backupUseCase;
	}

	@Override
	public void start(Stage stage) {
		root = new BorderPane();
		buildMainView();
		showLoginView();
		root.setStyle("-fx-background-color: #f7f8fa;");
		Scene scene = new Scene(root, 900, 680);
		scene.getStylesheets().add("/login.css");
		stage.setScene(scene);
		stage.setMinWidth(720);
		stage.setMinHeight(480);
		stage.setResizable(true);
		stage.setOnCloseRequest(event -> Platform.exit());
		stage.show();
	}

	private void showLoginView() {
		root.setTop(null);
		root.setCenter(loginView);
	}

	private void showMainView() {
		root.setTop(header.node());
		root.setCenter(tabs);
	}

	/** The signed-out screen: just the sign-in button and its connection status. */
	private Node buildLoginView() {
		Label title = new Label("Google Drive Backup");
		title.getStyleClass().add("title");
		Label subtitle = new Label("Sign in to continue");
		subtitle.getStyleClass().add("subtitle");
		Label scope = new Label("Read-only access to Google Drive");
		scope.getStyleClass().add("scope");
		connectionStatus.getStyleClass().add("status");
		connectionStatus.setWrapText(true);
		connectionStatus.setMaxWidth(540);
		signIn.getStyleClass().add("primary-button");
		signIn.setOnAction(event -> authenticate());
		VBox box = new VBox(12, title, subtitle, signIn, scope, connectionStatus);
		box.setAlignment(Pos.CENTER);
		return box;
	}

	/** Builds the signed-in window: the common header above the Backup, Archives and Technical info tabs. */
	private void buildMainView() {
		loginView = buildLoginView();
		header = new SessionHeaderPanel(user -> reloadForSelectedUser(), this::signOut);
		technicalInfoPanel = new TechnicalInfoPanel(serviceAccountUseCase, driveUsageQuotaUseCase,
				workspaceUsageReportUseCase, cloudQuotaLimitUseCase, this::selectedUserEmail);

		locationsPanel = new LocationsPanel(syncNow);
		progressPanel = new OperationProgressPanel(backupProgressPort, backupCancellationUseCase,
				"Starting synchronization...");
		archiveProgressPanel = new OperationProgressPanel(backupProgressPort, backupCancellationUseCase,
				"Starting archive operation...");
		archiveManagerPanel = new ArchiveManagerPanel(archiveCatalogUseCase, archiveMergeUseCase,
				archiveDeletionUseCase, () -> {
					archiveProgressPanel.start();
					syncNow.setDisable(true);
					locationsPanel.setChangesDisabled(true);
				}, () -> {
					archiveProgressPanel.stop();
					syncNow.setDisable(false);
					locationsPanel.setChangesDisabled(false);
				});

		fileHistoryPanel = new FileHistoryPanel(fileHistoryUseCase);

		tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
		tabs.getTabs().addAll(new Tab("Backup", scrollable(buildBackupTab())),
				new Tab("Archives", scrollable(buildArchivesTab())),
				new Tab("History", scrollable(fileHistoryPanel.node())),
				new Tab("Technical info", scrollable(technicalInfoPanel.node())));
	}

	private Node buildBackupTab() {
		driveStatus.getStyleClass().add("status");
		driveStatus.setWrapText(true);
		driveStatus.setMaxWidth(540);
		drivesLabel.getStyleClass().add("scope");
		hide(drivesLabel);
		drives.setPlaceholder(new Label("No drives loaded"));
		hide(drives);
		drives.setCellFactory(CheckBoxListCell.forListView(
				drive -> driveSelections.computeIfAbsent(drive, key -> new SimpleBooleanProperty(false)),
				new StringConverter<AvailableDrive>() {
					@Override
					public String toString(AvailableDrive drive) {
						return drive == null ? "" : (drive.shared() ? "Shared: " : "") + drive.name();
					}

					@Override
					public AvailableDrive fromString(String string) {
						return null;
					}
				}));
		driveItems.setPlaceholder(new Label("No items loaded"));
		hide(driveItems);
		driveItems.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
			@Override
			protected void updateItem(DriveItem item, boolean empty) {
				super.updateItem(item, empty);
				setText(empty || item == null ? null : (item.folder() ? "[Folder] " : "") + item.name());
			}
		});
		backupModeCombo.getItems().setAll(BackupMode.INCREMENTAL, BackupMode.FULL);
		backupModeCombo.setValue(BackupMode.INCREMENTAL);
		backupModeCombo.setCellFactory(view -> backupModeCell());
		backupModeCombo.setButtonCell(backupModeCell());
		hide(backupModeCombo);
		syncNow.getStyleClass().add("primary-button");
		hide(syncNow);
		syncNow.setOnAction(event -> synchronizeSelectedUser());

		drives.setOnMouseClicked(event -> {
			AvailableDrive selected = drives.getSelectionModel().getSelectedItem();
			if (selected != null && event.getClickCount() >= 1) {
				loadDriveContents(selectedUserEmail(), selected);
			}
		});
		driveItems.setOnMouseClicked(event -> {
			DriveItem selected = driveItems.getSelectionModel().getSelectedItem();
			if (selected != null && event.getClickCount() >= 2 && selected.folder()) {
				loadFolderContents(selectedUserEmail(), selected);
			}
		});

		VBox content = new VBox(12, locationsPanel.node(), drivesLabel, drives, backupModeCombo, syncNow,
				progressPanel.node(), driveStatus, driveItems);
		content.setAlignment(Pos.CENTER);
		content.setMaxWidth(560);
		content.setPadding(new Insets(12));
		return content;
	}

	private Node buildArchivesTab() {
		VBox content = new VBox(12, archiveManagerPanel.node(), archiveProgressPanel.node());
		content.setAlignment(Pos.CENTER);
		content.setMaxWidth(560);
		content.setPadding(new Insets(12));
		return content;
	}

	private static Node scrollable(Node content) {
		ScrollPane scrollPane = new ScrollPane(content);
		scrollPane.setFitToWidth(true);
		scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
		return scrollPane;
	}

	private static void hide(Node node) {
		node.setVisible(false);
		node.setManaged(false);
	}

	private static void show(Node node) {
		node.setVisible(true);
		node.setManaged(true);
	}

	private void authenticate() {
		signIn.setDisable(true);
		connectionStatus.setText("Waiting for Google sign-in...");
		driveStatus.setText("");
		CompletableFuture.supplyAsync(() -> loginUseCase.login(this::requestAuthorization))
				.whenComplete((loginSession, error) -> Platform.runLater(() -> {
					signIn.setDisable(false);
					if (error != null) {
						connectionStatus.setText(messageFor(error));
						return;
					}
					session = loginSession;
					previewUserEmail = loginSession.userEmail();
					connectionStatus.setText("");
					header.show();
					header.setStatus("Google connected");
					showMainView();
					locationsPanel.show();
					archiveManagerPanel.show();
					fileHistoryPanel.show();
					loadWorkspaceUsers();
				}));
	}

	private void signOut() {
		if (session != null) {
			loginUseCase.logout(session);
			session = null;
		}
		previewUserEmail = null;
		header.hide();
		driveStatus.setText("");
		hide(syncNow);
		hide(backupModeCombo);
		backupModeCombo.setValue(BackupMode.INCREMENTAL);
		driveItems.getItems().clear();
		hide(driveItems);
		driveSelections.clear();
		drives.getItems().clear();
		hide(drives);
		hide(drivesLabel);
		locationsPanel.hide();
		archiveManagerPanel.hide();
		fileHistoryPanel.hide();
		technicalInfoPanel.hide();
		tabs.getSelectionModel().selectFirst();
		showLoginView();
	}

	/** The Workspace user every view works on: the header's selection, else the configured user. */
	private String selectedUserEmail() {
		return header.selectedEmail(previewUserEmail);
	}

	/** The selected user changed (or was first chosen): reload their drives and drop their stale technical data. */
	private void reloadForSelectedUser() {
		loadDrives();
		technicalInfoPanel.onUserChanged();
	}

	private void loadWorkspaceUsers() {
		if (serviceAccountUseCase == null || workspaceUserListingUseCase == null || previewUserEmail == null
				|| previewUserEmail.isBlank()) {
			header.showFixedUser(previewUserEmail);
			reloadForSelectedUser();
			return;
		}
		driveStatus.setText("Loading Workspace users...");
		CompletableFuture.supplyAsync(() -> {
			ServiceAccountAccess access = serviceAccountUseCase.authenticateAs(previewUserEmail);
			return workspaceUserListingUseCase.listUsers(access);
		}).whenComplete((users, error) -> Platform.runLater(() -> {
			if (error != null) {
				driveStatus.setText("Google connected, user selection unavailable: " + messageFor(error));
				header.showFixedUser(previewUserEmail);
				reloadForSelectedUser();
				return;
			}
			header.setUsers(users, previewUserEmail);
			if (!users.isEmpty()) {
				if (driveBackupUseCase != null) {
					show(syncNow);
					show(backupModeCombo);
				}
			}
			reloadForSelectedUser();
		}));
	}

	private void synchronizeSelectedUser() {
		Label status = driveStatus;
		String selectedUserEmail = selectedUserEmail();
		if (driveBackupUseCase == null || serviceAccountUseCase == null
				|| selectedUserEmail == null || selectedUserEmail.isBlank()) {
			status.setText("Sync unavailable. Select a Workspace user and configure service-account access.");
			return;
		}
		List<AvailableDrive> selectedDrives = drives.getItems().stream()
				.filter(drive -> driveSelections.getOrDefault(drive, new SimpleBooleanProperty(false)).get())
				.toList();
		if (selectedDrives.isEmpty()) {
			status.setText("Select at least one drive to back up.");
			return;
		}
		BackupMode mode = backupModeCombo.getValue();
		syncNow.setDisable(true);
		backupModeCombo.setDisable(true);
		locationsPanel.setChangesDisabled(true);
		archiveManagerPanel.setExternallyBusy(true);
		String destination = backupLocationUseCase == null ? ""
				: " into " + backupLocationUseCase.currentLocation().root();
		status.setText("Synchronizing " + modeLabel(mode).toLowerCase() + " backup for " + selectedUserEmail
				+ destination + "...");
		progressPanel.start();
		CompletableFuture.supplyAsync(() -> {
			ServiceAccountAccess access = serviceAccountUseCase.authenticateAs(selectedUserEmail);
			return driveBackupUseCase.synchronizeSelectedDrives(access, selectedDrives, mode);
		}).whenComplete((results, error) -> Platform.runLater(() -> {
			syncNow.setDisable(false);
			backupModeCombo.setDisable(false);
			locationsPanel.setChangesDisabled(false);
			archiveManagerPanel.setExternallyBusy(false);
			progressPanel.stop();
			if (error != null) {
				status.setText("Synchronization failed: " + messageFor(error));
				return;
			}
			boolean cancelled = results.size() < selectedDrives.size()
					|| results.stream().anyMatch(BackupResult::cancelled);
			status.setText(BackupSummaryText.summary(results, selectedDrives, cancelled));
		}));
	}

	private static String modeLabel(BackupMode mode) {
		return mode == BackupMode.FULL ? "Full" : "Incremental";
	}

	private static javafx.scene.control.ListCell<BackupMode> backupModeCell() {
		return new javafx.scene.control.ListCell<>() {
			@Override
			protected void updateItem(BackupMode mode, boolean empty) {
				super.updateItem(mode, empty);
				setText(empty || mode == null ? null : modeLabel(mode) + " backup");
			}
		};
	}

	private void loadDrives() {
		Label status = driveStatus;
		String selectedUserEmail = selectedUserEmail();
		if (serviceAccountUseCase == null || driveReadPort == null || selectedUserEmail == null
				|| selectedUserEmail.isBlank()) {
			driveItems.getItems().clear();
			driveItems.setVisible(false);
			driveItems.setManaged(false);
			status.setText("Google connected, Drive preview unavailable. Configure GOOGLE_SERVICE_ACCOUNT_KEY.");
			return;
		}
		status.setText("Loading available drives for " + selectedUserEmail + "...");
		CompletableFuture.supplyAsync(() -> {
			ServiceAccountAccess access = serviceAccountUseCase.authenticateAs(selectedUserEmail);
			return driveReadPort.listAvailableDrives(access);
		}).whenComplete((availableDrives, error) -> Platform.runLater(() -> {
			if (error != null) {
				status.setText("Google connected, Drive preview unavailable: " + messageFor(error));
				driveItems.getItems().clear();
				driveItems.setVisible(false);
				driveItems.setManaged(false);
				return;
			}
			driveSelections.clear();
			drives.getItems().setAll(availableDrives);
			drives.setVisible(true);
			drives.setManaged(true);
			drivesLabel.setVisible(true);
			drivesLabel.setManaged(true);
			driveItems.getItems().clear();
			driveItems.setVisible(false);
			driveItems.setManaged(false);
			status.setText("Available drives for " + selectedUserEmail);
		}));
	}

	private void loadDriveContents(String userEmail, AvailableDrive selectedDrive) {
		Label status = driveStatus;
		if (selectedDrive == null || serviceAccountUseCase == null || driveReadPort == null) {
			return;
		}
		status.setText("Loading contents for " + selectedDrive.name() + "...");
		CompletableFuture.supplyAsync(() -> {
			ServiceAccountAccess access = serviceAccountUseCase.authenticateAs(userEmail);
			if (selectedDrive.shared()) {
				return driveReadPort.listSharedDriveItems(access, selectedDrive.id());
			}
			return driveReadPort.listMyDriveItems(access, "root");
		}).whenComplete((items, error) -> Platform.runLater(() -> {
			if (error != null) {
				status.setText("Unable to load contents: " + messageFor(error));
				return;
			}
			driveItems.getItems().setAll(items);
			driveItems.setVisible(true);
			driveItems.setManaged(true);
			status.setText("Contents for " + selectedDrive.name());
		}));
	}

	private void loadFolderContents(String userEmail, DriveItem selectedItem) {
		Label status = driveStatus;
		if (selectedItem == null || serviceAccountUseCase == null || driveReadPort == null) {
			return;
		}
		status.setText("Loading folder: " + selectedItem.name() + "...");
		CompletableFuture.supplyAsync(() -> {
			ServiceAccountAccess access = serviceAccountUseCase.authenticateAs(userEmail);
			if (selectedItem.driveId() != null && !selectedItem.driveId().isBlank()) {
				return driveReadPort.listSharedDriveItems(access, selectedItem.driveId());
			}
			return driveReadPort.listMyDriveItems(access, selectedItem.id());
		}).whenComplete((items, error) -> Platform.runLater(() -> {
			if (error != null) {
				status.setText("Unable to load folder: " + messageFor(error));
				return;
			}
			driveItems.getItems().setAll(items);
			driveItems.setVisible(true);
			driveItems.setManaged(true);
			status.setText("Folder contents: " + selectedItem.name());
		}));
	}

	private static String messageFor(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		return current.getMessage() == null ? "Google authentication failed" : current.getMessage();
	}

	private boolean requestAuthorization(URI authorizationUri) {
		CountDownLatch completed = new CountDownLatch(1);
		AtomicBoolean approved = new AtomicBoolean(false);
		Platform.runLater(() -> {
			Alert prompt = new Alert(Alert.AlertType.CONFIRMATION);
			prompt.setTitle("Google authorization");
			prompt.setHeaderText("Open Google authorization in your browser?");
			prompt.setContentText("Google will grant read-only Drive access.\n\n" + authorizationUri);
			prompt.getDialogPane().setMinWidth(520);
			if (prompt.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
				try {
					openBrowser(authorizationUri);
					approved.set(true);
				} catch (IOException | UnsupportedOperationException exception) {
				}
			}
			completed.countDown();
		});
		try {
			completed.await();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
		return approved.get();
	}

	private static void openBrowser(URI uri) throws IOException {
		if (isWsl()) {
			try {
				new ProcessBuilder("wslview", uri.toString()).start();
				return;
			} catch (IOException ignored) {
				String windowsUrl = uri.toString().replace("&", "^&");
				new ProcessBuilder("cmd.exe", "/c", "start", "", windowsUrl).start();
			}
			return;
		}
		if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
			throw new IOException("System browser is not available");
		}
		Desktop.getDesktop().browse(uri);
	}

	private static boolean isWsl() {
		if (System.getenv("WSL_DISTRO_NAME") != null) {
			return true;
		}
		try {
			String kernelVersion = Files.readString(Path.of("/proc/version"));
			return kernelVersion.contains("Microsoft") || kernelVersion.contains("microsoft");
		} catch (IOException exception) {
			return false;
		}
	}

	/** Shows the active backup locations and lets the admin change them for the current session. */
	private final class LocationsPanel {

		private final Button syncNow;
		private final Label locationValue = pathLabel();
		private final Label status = new Label();
		private final Button changeLocation = new Button("Change location...");
		private final VBox root;

		LocationsPanel(Button syncNow) {
			this.syncNow = syncNow;
			Label title = new Label("Backup location");
			title.getStyleClass().add("subtitle");
			Label locationHeader = new Label("Backup root (history database and archives)");
			Label sessionNote = new Label("Changes apply to this session only.");
			sessionNote.getStyleClass().add("scope");
			status.getStyleClass().add("status");
			status.setWrapText(true);
			status.setMaxWidth(540);
			changeLocation.getStyleClass().add("secondary-button");
			changeLocation.setOnAction(event -> chooseBackupLocation());
			root = new VBox(6, title, locationHeader, locationValue, changeLocation, sessionNote, status);
			root.setAlignment(Pos.CENTER);
			hide();
		}

		Node node() {
			return root;
		}

		void show() {
			if (backupLocationUseCase == null) {
				hide();
				return;
			}
			refresh();
			root.setVisible(true);
			root.setManaged(true);
		}

		void hide() {
			root.setVisible(false);
			root.setManaged(false);
			status.setText("");
		}

		void setChangesDisabled(boolean disabled) {
			changeLocation.setDisable(disabled);
		}

		private void refresh() {
			BackupLocation location = backupLocationUseCase.currentLocation();
			locationValue.setText(location.root().toString());
		}

		private void chooseBackupLocation() {
			BackupLocation current = backupLocationUseCase.currentLocation();
			DirectoryChooser chooser = new DirectoryChooser();
			chooser.setTitle("Choose backup location");
			chooser.setInitialDirectory(existingDirectory(current.root()));
			File selected = chooser.showDialog(root.getScene().getWindow());
			if (selected != null) {
				validateAndChange(selected.toPath(), backupLocationUseCase::validateRoot,
						backupLocationUseCase::changeRoot,
						validation -> locationPrompt(current, selected.toPath(), validation));
			}
		}

		private void validateAndChange(Path selected, Function<Path, LocationValidation> validate,
				Function<Path, BackupLocation> change, Function<LocationValidation, String> prompt) {
			setBusy(true);
			status.setText("Checking " + selected + "...");
			CompletableFuture.supplyAsync(() -> validate.apply(selected))
					.whenComplete((validation, error) -> Platform.runLater(() -> {
						if (error != null) {
							setBusy(false);
							status.setText("Unable to check location: " + messageFor(error));
							return;
						}
						switch (validation.status()) {
							case UNCHANGED -> {
								setBusy(false);
								status.setText("That location is already in use.");
							}
							case INVALID -> {
								setBusy(false);
								status.setText("");
								showError(validation.detail());
							}
							default -> {
								if (confirm(prompt.apply(validation))) {
									apply(selected, change);
								} else {
									setBusy(false);
									status.setText("Location unchanged.");
								}
							}
						}
					}));
		}

		private void apply(Path selected, Function<Path, BackupLocation> change) {
			status.setText("Switching to " + selected + "...");
			CompletableFuture.supplyAsync(() -> change.apply(selected))
					.whenComplete((location, error) -> Platform.runLater(() -> {
						setBusy(false);
						if (error != null) {
							status.setText("Location not changed: " + messageFor(error));
							return;
						}
						refresh();
						status.setText("Location changed.");
					}));
		}

		private void setBusy(boolean busy) {
			setChangesDisabled(busy);
			syncNow.setDisable(busy);
		}

		private boolean confirm(String message) {
			Alert prompt = new Alert(Alert.AlertType.CONFIRMATION);
			prompt.setTitle("Backup location");
			prompt.setHeaderText("Change backup location?");
			prompt.setContentText(message);
			prompt.getDialogPane().setMinWidth(520);
			return prompt.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
		}

		private void showError(String message) {
			Alert alert = new Alert(Alert.AlertType.ERROR);
			alert.setTitle("Backup location");
			alert.setHeaderText("This location can't be used");
			alert.setContentText(message);
			alert.getDialogPane().setMinWidth(520);
			alert.showAndWait();
		}

		private static String locationPrompt(BackupLocation current, Path selected, LocationValidation validation) {
			String existingNote = validation.status() == LocationStatus.EXISTING
					? "\n\n" + validation.detail() + " Existing history and files are kept."
					: "";
			return "The backup history database and archives will be written to:\n" + selected + existingNote
					+ "\n\nThe current backup location stays where it is:\n" + current.root();
		}

		private static File existingDirectory(Path path) {
			Path candidate = path;
			while (candidate != null && !Files.isDirectory(candidate)) {
				candidate = candidate.getParent();
			}
			return candidate == null ? null : candidate.toFile();
		}

		private static Label pathLabel() {
			Label label = new Label();
			label.setStyle("-fx-font-family: monospace; -fx-text-fill: #172033;");
			label.setWrapText(true);
			label.setMaxWidth(540);
			return label;
		}
	}

	@Override
	public void stop() {
		if (springContext != null) {
			springContext.close();
		}
	}

}
