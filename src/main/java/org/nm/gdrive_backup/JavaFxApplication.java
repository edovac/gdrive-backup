package org.nm.gdrive_backup;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.springframework.context.ConfigurableApplicationContext;
import org.nm.gdrive_backup.domain.model.GoogleLoginSession;
import org.nm.gdrive_backup.domain.port.in.GoogleLoginUseCase;
import org.nm.gdrive_backup.domain.port.in.DriveUsageQuotaUseCase;
import org.nm.gdrive_backup.domain.port.in.WorkspaceUsageReportUseCase;
import org.nm.gdrive_backup.domain.port.in.CloudQuotaLimitUseCase;
import org.nm.gdrive_backup.domain.port.in.ServiceAccountAuthenticationUseCase;
import org.nm.gdrive_backup.domain.port.in.WorkspaceUserListingUseCase;
import org.nm.gdrive_backup.domain.port.in.DriveBackupUseCase;
import org.nm.gdrive_backup.domain.port.in.DriveUserProfileUseCase;
import org.nm.gdrive_backup.domain.model.ApplicationInfo;
import org.nm.gdrive_backup.adapter.in.javafx.ApplicationInfoText;
import org.nm.gdrive_backup.adapter.in.javafx.ArchiveManagerPanel;
import org.nm.gdrive_backup.adapter.in.javafx.BackupDrivePanel;
import org.nm.gdrive_backup.adapter.in.javafx.BackupModePicker;
import org.nm.gdrive_backup.adapter.in.javafx.BackupSummaryText;
import org.nm.gdrive_backup.adapter.in.javafx.FileHistoryPanel;
import org.nm.gdrive_backup.adapter.in.javafx.OperationProgressPanel;
import org.nm.gdrive_backup.adapter.in.javafx.SessionHeaderPanel;
import org.nm.gdrive_backup.adapter.in.javafx.SettingsPanel;
import org.nm.gdrive_backup.adapter.in.javafx.TechnicalInfoPanel;
import org.nm.gdrive_backup.domain.port.in.ArchiveCatalogUseCase;
import org.nm.gdrive_backup.domain.port.in.DownloadConcurrencyUseCase;
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
import org.nm.gdrive_backup.domain.port.in.CredentialConfigurationUseCase;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
	private static DriveUserProfileUseCase driveUserProfileUseCase;
	private static ApplicationInfo applicationInfo = ApplicationInfo.unknown();
	private static String previewUserEmail;
	private static BackupLocationUseCase backupLocationUseCase;
	private static CredentialConfigurationUseCase credentialConfigurationUseCase;
	private static DownloadConcurrencyUseCase downloadConcurrencyUseCase;
	private static BackupProgressPort backupProgressPort;
	private static BackupCancellationUseCase backupCancellationUseCase;
	private static ArchiveCatalogUseCase archiveCatalogUseCase;
	private static ArchiveMergeUseCase archiveMergeUseCase;
	private static org.nm.gdrive_backup.domain.port.in.FileHistoryUseCase fileHistoryUseCase;
	private static ArchiveDeletionUseCase archiveDeletionUseCase;

	private final Button signIn = new Button("Sign in with Google");
	private final Label connectionStatus = new Label();
	private final Label driveStatus = new Label();
	private final ListView<DriveItem> driveItems = new ListView<>();
	private final Button syncNow = new Button("Sync selected drives");
	private final Label footerSummary = new Label();
	private HBox idleFooterRow;
	private final BackupDrivePanel drivePanel = new BackupDrivePanel();
	private final BackupModePicker modePicker = new BackupModePicker();
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
	private SettingsPanel settingsPanel;
	private Tab backupTab;

	static void setSpringContext(ConfigurableApplicationContext context) {
		springContext = context;
	}

	static void setApplicationInfo(ApplicationInfo info) {
		applicationInfo = info == null ? ApplicationInfo.unknown() : info;
	}

	static void setLoginUseCase(GoogleLoginUseCase useCase) {
		loginUseCase = useCase;
	}

	static void setBackupLocationUseCase(BackupLocationUseCase useCase) {
		backupLocationUseCase = useCase;
	}

	static void setCredentialConfigurationUseCase(CredentialConfigurationUseCase useCase) {
		credentialConfigurationUseCase = useCase;
	}

	static void setDownloadConcurrencyUseCase(DownloadConcurrencyUseCase useCase) {
		downloadConcurrencyUseCase = useCase;
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
			CloudQuotaLimitUseCase cloudQuotaUseCase, DriveBackupUseCase backupUseCase,
			DriveUserProfileUseCase userProfileUseCase) {
		serviceAccountUseCase = authenticationUseCase;
		driveReadPort = readPort;
		workspaceUserListingUseCase = workspaceUserUseCase;
		driveUsageQuotaUseCase = usageQuotaUseCase;
		workspaceUsageReportUseCase = usageReportUseCase;
		cloudQuotaLimitUseCase = cloudQuotaUseCase;
		driveBackupUseCase = backupUseCase;
		driveUserProfileUseCase = userProfileUseCase;
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
		Label version = new Label(ApplicationInfoText.versionLabel(applicationInfo));
		version.getStyleClass().add("scope");
		Label scope = new Label("Read-only access to Google Drive");
		scope.getStyleClass().add("scope");
		connectionStatus.getStyleClass().add("status");
		connectionStatus.setWrapText(true);
		connectionStatus.setMaxWidth(540);
		signIn.getStyleClass().add("primary-button");
		signIn.setOnAction(event -> authenticate());
		Button settingsButton = new Button("Settings");
		settingsButton.getStyleClass().add("secondary-button");
		settingsButton.setOnAction(event -> showSettings());
		VBox box = new VBox(12, title, version, subtitle, signIn, settingsButton, scope, connectionStatus);
		box.setAlignment(Pos.CENTER);
		return box;
	}

	/** Opens the credential/project-id Settings screen as a modal dialog, reachable signed in or out. */
	private void showSettings() {
		if (credentialConfigurationUseCase == null) {
			return;
		}
		if (settingsPanel == null) {
			settingsPanel = new SettingsPanel(credentialConfigurationUseCase, downloadConcurrencyUseCase);
		} else {
			settingsPanel.refresh();
		}
		Stage dialog = new Stage();
		dialog.initModality(Modality.APPLICATION_MODAL);
		dialog.initOwner(root.getScene().getWindow());
		dialog.setTitle("Settings");
		Scene scene = new Scene((Parent) scrollable(settingsPanel.node()), 480, 560);
		scene.getStylesheets().add("/login.css");
		dialog.setScene(scene);
		dialog.showAndWait();
	}

	/** Builds the signed-in window: the common header above the Backup, Archives and Technical info tabs. */
	private void buildMainView() {
		loginView = buildLoginView();
		header = new SessionHeaderPanel(applicationInfo, user -> reloadForSelectedUser(), this::signOut,
				this::showSettings);
		technicalInfoPanel = new TechnicalInfoPanel(serviceAccountUseCase, driveUsageQuotaUseCase,
				workspaceUsageReportUseCase, cloudQuotaLimitUseCase, applicationInfo, this::selectedUserEmail);

		locationsPanel = new LocationsPanel(syncNow);
		locationsPanel.onChanged(() -> {
			updateBackupFooter();
			refreshArchiveCatalog();
		});
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

		backupTab = new Tab("Backup", buildBackupTab());
		tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
		tabs.getTabs().addAll(backupTab,
				new Tab("Archives", scrollable(buildArchivesTab())),
				new Tab("History", scrollable(fileHistoryPanel.node())),
				new Tab("Technical info", scrollable(technicalInfoPanel.node())));
		tabs.getSelectionModel().selectedItemProperty().addListener((observable, was, now) -> {
			if (now == backupTab) {
				refreshArchiveCatalog();
			}
		});
	}

	/**
	 * The Backup tab: three numbered steps (Where / What / How) above a collapsible contents preview, with a
	 * footer bar that summarizes the pending run and starts it — replaced by the shared progress panel while
	 * one is in flight.
	 */
	private Node buildBackupTab() {
		driveStatus.getStyleClass().add("status");
		driveStatus.setWrapText(true);
		driveStatus.setMaxWidth(660);

		Node whereCard = stepCard(1, "Where", locationsPanel.node());
		Node whatCard = stepCard(2, "What", new VBox(8, drivePanel.node(), driveStatus));
		Node howCard = stepCard(3, "How", modePicker.node());

		driveItems.setPlaceholder(new Label("No items loaded"));
		driveItems.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
			@Override
			protected void updateItem(DriveItem item, boolean empty) {
				super.updateItem(item, empty);
				setText(empty || item == null ? null : (item.folder() ? "[Folder] " : "") + item.name());
			}
		});
		driveItems.setOnMouseClicked(event -> {
			DriveItem selected = driveItems.getSelectionModel().getSelectedItem();
			if (selected != null && event.getClickCount() >= 2 && selected.folder()) {
				loadFolderContents(selectedUserEmail(), selected);
			}
		});
		TitledPane previewPane = new TitledPane("Preview drive contents", driveItems);
		previewPane.setExpanded(false);
		previewPane.expandedProperty().addListener((observable, was, expanded) -> {
			if (expanded) {
				loadDriveContents(selectedUserEmail(), drivePanel.focusedDrive());
			}
		});
		drivePanel.onFocusChanged(drive -> {
			if (previewPane.isExpanded()) {
				loadDriveContents(selectedUserEmail(), drive);
			}
		});
		drivePanel.onSelectionChanged(this::updateBackupFooter);
		modePicker.onChange(this::updateBackupFooter);

		syncNow.getStyleClass().add("primary-button");
		hide(syncNow);
		syncNow.setOnAction(event -> synchronizeSelectedUser());
		footerSummary.getStyleClass().add("status");
		footerSummary.setWrapText(true);
		idleFooterRow = new HBox(14, footerSummary, syncNow);
		idleFooterRow.setAlignment(Pos.CENTER_LEFT);
		HBox.setHgrow(footerSummary, Priority.ALWAYS);
		updateBackupFooter();

		// The progress bar lives in the pinned footer, not the scrollable cards above, so it stays visible
		// (and prominent) no matter how far the admin has scrolled or how tall the drive list has grown.
		VBox footer = new VBox(10, idleFooterRow, progressPanel.node());
		footer.getStyleClass().add("footer-bar");

		VBox cards = new VBox(14, whereCard, whatCard, howCard, previewPane);
		cards.setMaxWidth(720);
		cards.setPadding(new Insets(16, 0, 16, 0));
		VBox centered = new VBox(cards);
		centered.setAlignment(Pos.TOP_CENTER);

		BorderPane layout = new BorderPane();
		layout.setCenter(scrollable(centered));
		layout.setBottom(footer);
		return layout;
	}

	private static Node stepCard(int number, String title, Node body) {
		Label numberLabel = new Label(Integer.toString(number));
		numberLabel.getStyleClass().add("step-number");
		Label titleLabel = new Label(title);
		titleLabel.getStyleClass().add("step-title");
		HBox header = new HBox(10, numberLabel, titleLabel);
		header.setAlignment(Pos.CENTER_LEFT);
		VBox card = new VBox(10, header, body);
		card.getStyleClass().add("step-card");
		return card;
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
					header.showAdmin(loginSession.userEmail());
					showMainView();
					locationsPanel.show();
					archiveManagerPanel.show();
					fileHistoryPanel.show();
					loadWorkspaceUsers();
					loadAdminProfile(loginSession);
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
		modePicker.reset();
		drivePanel.clear();
		driveItems.getItems().clear();
		hide(driveItems);
		updateBackupFooter();
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
			if (!users.isEmpty() && driveBackupUseCase != null) {
				show(syncNow);
			}
			reloadForSelectedUser();
		}));
	}

	/**
	 * Fetches the signed-in admin's Drive profile (name, photo) for the header avatar. Best-effort: on failure the
	 * header keeps showing initials from the email alone. Guarded against a sign-out that happens while this is
	 * in flight, since the result would otherwise land on a header that has moved on to a new session or none.
	 */
	private void loadAdminProfile(GoogleLoginSession loginSession) {
		if (serviceAccountUseCase == null || driveUserProfileUseCase == null) {
			return;
		}
		CompletableFuture.supplyAsync(() -> {
			ServiceAccountAccess access = serviceAccountUseCase.authenticateAs(loginSession.userEmail());
			return driveUserProfileUseCase.getProfile(access);
		}).whenComplete((profile, error) -> Platform.runLater(() -> {
			if (error == null && session == loginSession) {
				header.showAdminProfile(profile);
			}
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
		List<AvailableDrive> selectedDrives = drivePanel.selectedDrives();
		if (selectedDrives.isEmpty()) {
			status.setText("Select at least one drive to back up.");
			return;
		}
		BackupMode mode = modePicker.value();
		syncNow.setDisable(true);
		modePicker.setDisabled(true);
		drivePanel.setDisabled(true);
		locationsPanel.setChangesDisabled(true);
		archiveManagerPanel.setExternallyBusy(true);
		String destination = backupLocationUseCase == null ? ""
				: " into " + backupLocationUseCase.currentLocation().root();
		status.setText("Synchronizing " + modeLabel(mode).toLowerCase() + " backup for " + selectedUserEmail
				+ destination + "...");
		drivePanel.runStarted(selectedDrives);
		progressPanel.setProgressListener(drivePanel::onProgress);
		hide(idleFooterRow);
		progressPanel.start();
		CompletableFuture.supplyAsync(() -> {
			ServiceAccountAccess access = serviceAccountUseCase.authenticateAs(selectedUserEmail);
			return driveBackupUseCase.synchronizeSelectedDrives(access, selectedDrives, mode);
		}).whenComplete((results, error) -> Platform.runLater(() -> {
			syncNow.setDisable(false);
			modePicker.setDisabled(false);
			drivePanel.setDisabled(false);
			locationsPanel.setChangesDisabled(false);
			archiveManagerPanel.setExternallyBusy(false);
			progressPanel.stop();
			show(idleFooterRow);
			if (error != null) {
				status.setText("Synchronization failed: " + messageFor(error));
				updateBackupFooter();
				return;
			}
			boolean cancelled = results.size() < selectedDrives.size()
					|| results.stream().anyMatch(BackupResult::cancelled);
			drivePanel.runFinished(results);
			status.setText(BackupSummaryText.summary(results, selectedDrives, cancelled));
			footerSummary.setText(BackupSummaryText.headline(results, cancelled));
			refreshArchiveCatalog();
		}));
	}

	private static String modeLabel(BackupMode mode) {
		return mode == BackupMode.FULL ? "Full" : "Incremental";
	}

	/** The ready-to-start summary and the Start button's enabled state, recomputed on any selection/mode change. */
	private void updateBackupFooter() {
		int selected = drivePanel.selectedDrives().size();
		syncNow.setDisable(selected == 0);
		if (selected == 0) {
			footerSummary.setText("Select at least one drive to back up.");
			return;
		}
		String destination = backupLocationUseCase == null ? ""
				: " → " + backupLocationUseCase.currentLocation().root();
		footerSummary.setText("Ready: " + modeLabel(modePicker.value()).toLowerCase() + " backup of " + selected
				+ " drive" + (selected == 1 ? "" : "s") + destination);
	}

	/** Refreshes each drive row's last-archive/chain state; called after drives load, a run ends, a location
	 * change, and whenever the Backup tab is (re)selected, since a merge or deletion on the Archives tab can
	 * change it. */
	private void refreshArchiveCatalog() {
		if (archiveCatalogUseCase == null) {
			return;
		}
		CompletableFuture.supplyAsync(archiveCatalogUseCase::listScopes)
				.whenComplete((scopes, error) -> Platform.runLater(() -> {
					if (error == null) {
						drivePanel.setCatalog(scopes);
					}
				}));
	}

	private void loadDrives() {
		Label status = driveStatus;
		String selectedUserEmail = selectedUserEmail();
		if (serviceAccountUseCase == null || driveReadPort == null || selectedUserEmail == null
				|| selectedUserEmail.isBlank()) {
			driveItems.getItems().clear();
			hide(driveItems);
			drivePanel.clear();
			updateBackupFooter();
			status.setText("Google connected, Drive preview unavailable. Import a service-account key in Settings.");
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
				hide(driveItems);
				drivePanel.clear();
				updateBackupFooter();
				return;
			}
			drivePanel.setDrives(availableDrives, selectedUserEmail);
			driveItems.getItems().clear();
			hide(driveItems);
			status.setText("Available drives for " + selectedUserEmail);
			updateBackupFooter();
			refreshArchiveCatalog();
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
		private Runnable onChanged = () -> {
		};

		LocationsPanel(Button syncNow) {
			this.syncNow = syncNow;
			locationValue.getStyleClass().add("path-chip");
			HBox pathRow = new HBox(10, locationValue, changeLocation);
			pathRow.setAlignment(Pos.CENTER_LEFT);
			Label sessionNote = new Label("Applies to this session only.");
			sessionNote.getStyleClass().add("scope");
			status.getStyleClass().add("status");
			status.setWrapText(true);
			status.setMaxWidth(600);
			changeLocation.getStyleClass().add("secondary-button");
			changeLocation.setOnAction(event -> chooseBackupLocation());
			root = new VBox(6, pathRow, sessionNote, status);
			hide();
		}

		Node node() {
			return root;
		}

		void onChanged(Runnable listener) {
			this.onChanged = listener == null ? () -> {
			} : listener;
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
						onChanged.run();
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
			label.setWrapText(true);
			label.setMaxWidth(480);
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
