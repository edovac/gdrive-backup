package org.nm.gdrive_backup;

import javafx.application.Application;
import javafx.application.Platform;
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
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.springframework.context.ConfigurableApplicationContext;
import org.nm.gdrive_backup.domain.model.GoogleLoginSession;
import org.nm.gdrive_backup.domain.model.DriveUsageQuota;
import org.nm.gdrive_backup.domain.model.CloudQuotaLimit;
import org.nm.gdrive_backup.domain.port.in.GoogleLoginUseCase;
import org.nm.gdrive_backup.domain.port.in.DriveUsageQuotaUseCase;
import org.nm.gdrive_backup.domain.port.in.WorkspaceUsageReportUseCase;
import org.nm.gdrive_backup.domain.port.in.CloudQuotaLimitUseCase;
import org.nm.gdrive_backup.domain.port.in.ServiceAccountAuthenticationUseCase;
import org.nm.gdrive_backup.domain.port.in.WorkspaceUserListingUseCase;
import org.nm.gdrive_backup.domain.port.in.DriveChangeSyncUseCase;
import org.nm.gdrive_backup.domain.port.out.DriveReadPort;
import org.nm.gdrive_backup.domain.model.AvailableDrive;
import org.nm.gdrive_backup.domain.model.DriveItem;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.WorkspaceUser;
import org.nm.gdrive_backup.domain.model.SyncResult;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
	private static DriveChangeSyncUseCase driveChangeSyncUseCase;
	private static String previewUserEmail;

	static void setSpringContext(ConfigurableApplicationContext context) {
		springContext = context;
	}

	static void setLoginUseCase(GoogleLoginUseCase useCase) {
		loginUseCase = useCase;
	}

	static void setDriveServices(ServiceAccountAuthenticationUseCase authenticationUseCase,
			DriveReadPort readPort, WorkspaceUserListingUseCase workspaceUserUseCase,
			DriveUsageQuotaUseCase usageQuotaUseCase, WorkspaceUsageReportUseCase usageReportUseCase,
			CloudQuotaLimitUseCase cloudQuotaUseCase, DriveChangeSyncUseCase changeSyncUseCase, String userEmail) {
		serviceAccountUseCase = authenticationUseCase;
		driveReadPort = readPort;
		workspaceUserListingUseCase = workspaceUserUseCase;
		driveUsageQuotaUseCase = usageQuotaUseCase;
		workspaceUsageReportUseCase = usageReportUseCase;
		cloudQuotaLimitUseCase = cloudQuotaUseCase;
		driveChangeSyncUseCase = changeSyncUseCase;
		previewUserEmail = userEmail;
	}

	@Override
	public void start(Stage stage) {
		BorderPane root = new BorderPane();
		root.setCenter(loginView());
		root.setStyle("-fx-background-color: #f7f8fa;");
		Scene scene = new Scene(root, 640, 400);
		scene.getStylesheets().add("/login.css");
		stage.setScene(scene);
		stage.setMinWidth(640);
		stage.setMinHeight(400);
		stage.setResizable(true);
		stage.setOnCloseRequest(event -> Platform.exit());
		stage.show();
	}

	private Node loginView() {
		Label title = new Label("Google Drive Backup");
		title.getStyleClass().add("title");
		Label subtitle = new Label("Sign in to continue");
		subtitle.getStyleClass().add("subtitle");
		Label scope = new Label("Read-only access to Google Drive");
		scope.getStyleClass().add("scope");
		Label connectionStatus = new Label();
		connectionStatus.getStyleClass().add("status");
		Label driveStatus = new Label();
		driveStatus.getStyleClass().add("status");
		Button signIn = new Button("Sign in with Google");
		signIn.getStyleClass().add("primary-button");
		Button signOut = new Button("Sign out");
		Label quotaTitle = new Label("Drive storage usage");
		quotaTitle.getStyleClass().add("subtitle");
		Label quotaStatus = new Label();
		quotaStatus.getStyleClass().add("status");
		ListView<String> quotaDetails = new ListView<>();
		quotaDetails.setPlaceholder(new Label("No quota data loaded"));
		quotaDetails.setVisible(false);
		quotaDetails.setManaged(false);
		Button refreshQuota = new Button("Refresh usage");
		refreshQuota.setVisible(false);
		refreshQuota.setManaged(false);
		Label reportTitle = new Label("Workspace usage report");
		reportTitle.getStyleClass().add("subtitle");
		Label reportStatus = new Label();
		reportStatus.getStyleClass().add("status");
		ListView<String> reportDetails = new ListView<>();
		reportDetails.setPlaceholder(new Label("No Workspace report loaded"));
		reportDetails.setVisible(false);
		reportDetails.setManaged(false);
		Button refreshReport = new Button("Refresh report");
		refreshReport.setVisible(false);
		refreshReport.setManaged(false);
		Label cloudQuotaTitle = new Label("Cloud API quota limits");
		cloudQuotaTitle.getStyleClass().add("subtitle");
		Label cloudQuotaStatus = new Label();
		cloudQuotaStatus.getStyleClass().add("status");
		ListView<String> cloudQuotaDetails = new ListView<>();
		cloudQuotaDetails.setPlaceholder(new Label("No Cloud quota limits loaded"));
		cloudQuotaDetails.setVisible(false);
		cloudQuotaDetails.setManaged(false);
		Button refreshCloudQuota = new Button("Refresh API limits");
		refreshCloudQuota.setVisible(false);
		refreshCloudQuota.setManaged(false);
		ListView<AvailableDrive> drives = new ListView<>();
		drives.setPlaceholder(new Label("No drives loaded"));
		drives.setVisible(false);
		drives.setManaged(false);
		drives.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
			@Override
			protected void updateItem(AvailableDrive drive, boolean empty) {
				super.updateItem(drive, empty);
				setText(empty || drive == null ? null : (drive.shared() ? "Shared: " : "") + drive.name());
			}
		});
		ListView<DriveItem> driveItems = new ListView<>();
		driveItems.setPlaceholder(new Label("No items loaded"));
		driveItems.setVisible(false);
		driveItems.setManaged(false);
		driveItems.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
			@Override
			protected void updateItem(DriveItem item, boolean empty) {
				super.updateItem(item, empty);
				setText(empty || item == null ? null : (item.folder() ? "[Folder] " : "") + item.name());
			}
		});
		ComboBox<WorkspaceUser> userPicker = new ComboBox<>();
		userPicker.setPromptText("Select Workspace user");
		userPicker.setVisible(false);
		userPicker.setManaged(false);
		userPicker.setOnAction(event -> {
			if (userPicker.getValue() != null) {
				loadDrives(drives, driveStatus, userPicker, driveItems);
				loadQuota(userPicker, quotaStatus, quotaDetails, refreshQuota);
				loadWorkspaceReport(userPicker, reportStatus, reportDetails, refreshReport);
			}
		});
		Button syncNow = new Button("Sync selected user");
		syncNow.getStyleClass().add("primary-button");
		syncNow.setVisible(false);
		syncNow.setManaged(false);
		syncNow.setOnAction(event -> synchronizeSelectedUser(userPicker, syncNow, driveStatus));
		refreshQuota.setOnAction(event -> loadQuota(userPicker, quotaStatus, quotaDetails, refreshQuota));
		refreshReport.setOnAction(event -> loadWorkspaceReport(userPicker, reportStatus, reportDetails, refreshReport));
		refreshCloudQuota.setOnAction(event -> loadCloudQuota(cloudQuotaStatus, cloudQuotaDetails, refreshCloudQuota));
		signOut.getStyleClass().add("secondary-button");
		signOut.setVisible(false);
		signOut.setManaged(false);

		drives.setOnMouseClicked(event -> {
			AvailableDrive selected = drives.getSelectionModel().getSelectedItem();
			if (selected != null && event.getClickCount() >= 1) {
				loadDriveContents(selectedUserEmailFor(userPicker, previewUserEmail), selected, driveItems, driveStatus);
			}
		});
		driveItems.setOnMouseClicked(event -> {
			DriveItem selected = driveItems.getSelectionModel().getSelectedItem();
			if (selected != null && event.getClickCount() >= 2 && selected.folder()) {
				loadFolderContents(selectedUserEmailFor(userPicker, previewUserEmail), selected, driveItems, driveStatus);
			}
		});

		signIn.setOnAction(event -> authenticate(signIn, signOut, connectionStatus, driveStatus,
				drives, userPicker, driveItems, quotaStatus, quotaDetails, refreshQuota,
				reportStatus, reportDetails, refreshReport, cloudQuotaStatus, cloudQuotaDetails, refreshCloudQuota,
				syncNow));
		signOut.setOnAction(event -> {
			GoogleLoginSession session = (GoogleLoginSession) signOut.getUserData();
			loginUseCase.logout(session);
			signOut.setUserData(null);
			signOut.setVisible(false);
			signOut.setManaged(false);
			signIn.setVisible(true);
			signIn.setManaged(true);
			subtitle.setText("Sign in to continue");
			connectionStatus.setText("");
			driveStatus.setText("");
			userPicker.getItems().clear();
			userPicker.setValue(null);
			userPicker.setVisible(false);
			userPicker.setManaged(false);
			syncNow.setVisible(false);
			syncNow.setManaged(false);
			driveItems.getItems().clear();
			driveItems.setVisible(false);
			driveItems.setManaged(false);
			quotaStatus.setText("");
			quotaDetails.getItems().clear();
			quotaDetails.setVisible(false);
			quotaDetails.setManaged(false);
			refreshQuota.setVisible(false);
			refreshQuota.setManaged(false);
			reportStatus.setText("");
			reportDetails.getItems().clear();
			reportDetails.setVisible(false);
			reportDetails.setManaged(false);
			refreshReport.setVisible(false);
			refreshReport.setManaged(false);
			cloudQuotaStatus.setText("");
			cloudQuotaDetails.getItems().clear();
			cloudQuotaDetails.setVisible(false);
			cloudQuotaDetails.setManaged(false);
			refreshCloudQuota.setVisible(false);
			refreshCloudQuota.setManaged(false);
			drives.getItems().clear();
			drives.setVisible(false);
			drives.setManaged(false);
		});

		VBox content = new VBox(12, title, subtitle, signIn, signOut, scope, userPicker, syncNow,
				connectionStatus, quotaTitle, quotaStatus, quotaDetails, refreshQuota,
				reportTitle, reportStatus, reportDetails, refreshReport, driveStatus, drives, driveItems);
		content.getChildren().addAll(cloudQuotaTitle, cloudQuotaStatus, cloudQuotaDetails, refreshCloudQuota);
		content.setAlignment(Pos.CENTER);
		content.setMaxWidth(560);
		connectionStatus.setMaxWidth(540);
		quotaStatus.setMaxWidth(540);
		reportStatus.setMaxWidth(540);
		cloudQuotaStatus.setMaxWidth(540);
		driveStatus.setMaxWidth(540);
		connectionStatus.setWrapText(true);
		quotaStatus.setWrapText(true);
		reportStatus.setWrapText(true);
		cloudQuotaStatus.setWrapText(true);
		driveStatus.setWrapText(true);
		ScrollPane scrollPane = new ScrollPane(content);
		scrollPane.setFitToWidth(true);
		scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
		return scrollPane;
	}

	private void authenticate(Button signIn, Button signOut, Label connectionStatus,
			Label driveStatus, ListView<AvailableDrive> drives, ComboBox<WorkspaceUser> userPicker,
			ListView<DriveItem> driveItems, Label quotaStatus, ListView<String> quotaDetails,
			Button refreshQuota, Label reportStatus, ListView<String> reportDetails, Button refreshReport,
			Label cloudQuotaStatus, ListView<String> cloudQuotaDetails, Button refreshCloudQuota,
			Button syncNow) {
		signIn.setDisable(true);
		connectionStatus.setText("Waiting for Google sign-in...");
		driveStatus.setText("");
		CompletableFuture.supplyAsync(() -> loginUseCase.login(this::requestAuthorization))
				.whenComplete((session, error) -> Platform.runLater(() -> {
					signIn.setDisable(false);
					if (error != null) {
						connectionStatus.setText(messageFor(error));
						return;
					}
					signIn.setVisible(false);
					signIn.setManaged(false);
					signOut.setUserData(session);
					signOut.setVisible(true);
					signOut.setManaged(true);
					connectionStatus.setText("Google connected");
					loadCloudQuota(cloudQuotaStatus, cloudQuotaDetails, refreshCloudQuota);
					loadWorkspaceUsers(drives, userPicker, driveItems, driveStatus,
							quotaStatus, quotaDetails, refreshQuota, reportStatus, reportDetails, refreshReport, syncNow);
				}));
	}

	private void loadWorkspaceUsers(ListView<AvailableDrive> drives, ComboBox<WorkspaceUser> userPicker,
			ListView<DriveItem> driveItems, Label status, Label quotaStatus,
			ListView<String> quotaDetails, Button refreshQuota, Label reportStatus,
			ListView<String> reportDetails, Button refreshReport, Button syncNow) {
		if (serviceAccountUseCase == null || workspaceUserListingUseCase == null || previewUserEmail == null
				|| previewUserEmail.isBlank()) {
			userPicker.setVisible(false);
			userPicker.setManaged(false);
			loadDrives(drives, status, userPicker, driveItems);
			loadQuota(userPicker, quotaStatus, quotaDetails, refreshQuota);
			loadWorkspaceReport(userPicker, reportStatus, reportDetails, refreshReport);
			return;
		}
		status.setText("Loading Workspace users...");
		CompletableFuture.supplyAsync(() -> {
			ServiceAccountAccess access = serviceAccountUseCase.authenticateAs(previewUserEmail);
			return workspaceUserListingUseCase.listUsers(access);
		}).whenComplete((users, error) -> Platform.runLater(() -> {
			if (error != null) {
				status.setText("Google connected, user selection unavailable: " + messageFor(error));
				userPicker.setVisible(false);
				userPicker.setManaged(false);
				loadDrives(drives, status, userPicker, driveItems);
				loadQuota(userPicker, quotaStatus, quotaDetails, refreshQuota);
				loadWorkspaceReport(userPicker, reportStatus, reportDetails, refreshReport);
				return;
			}
			userPicker.getItems().setAll(users);
			if (users.isEmpty()) {
				userPicker.setVisible(false);
				userPicker.setManaged(false);
				loadDrives(drives, status, userPicker, driveItems);
				loadQuota(userPicker, quotaStatus, quotaDetails, refreshQuota);
				loadWorkspaceReport(userPicker, reportStatus, reportDetails, refreshReport);
				return;
			}
			WorkspaceUser selected = users.stream()
					.filter(user -> user.email().equalsIgnoreCase(previewUserEmail))
					.findFirst()
					.orElse(users.getFirst());
			userPicker.setValue(selected);
			userPicker.setVisible(true);
			userPicker.setManaged(true);
			syncNow.setVisible(driveChangeSyncUseCase != null);
			syncNow.setManaged(driveChangeSyncUseCase != null);
			loadDrives(drives, status, userPicker, driveItems);
			loadQuota(userPicker, quotaStatus, quotaDetails, refreshQuota);
			loadWorkspaceReport(userPicker, reportStatus, reportDetails, refreshReport);
		}));
	}

	private void synchronizeSelectedUser(ComboBox<WorkspaceUser> userPicker, Button syncNow, Label status) {
		String selectedUserEmail = selectedUserEmailFor(userPicker, previewUserEmail);
		if (driveChangeSyncUseCase == null || serviceAccountUseCase == null
				|| selectedUserEmail == null || selectedUserEmail.isBlank()) {
			status.setText("Sync unavailable. Select a Workspace user and configure service-account access.");
			return;
		}
		syncNow.setDisable(true);
		status.setText("Synchronizing changes for " + selectedUserEmail + "...");
		CompletableFuture.supplyAsync(() -> {
			ServiceAccountAccess access = serviceAccountUseCase.authenticateAs(selectedUserEmail);
			return driveChangeSyncUseCase.synchronize(access, selectedUserEmail);
		}).whenComplete((result, error) -> Platform.runLater(() -> {
			syncNow.setDisable(false);
			if (error != null) {
				status.setText("Synchronization failed: " + messageFor(error));
				return;
			}
			status.setText(syncMessage(result));
		}));
	}

	private static String syncMessage(SyncResult result) {
		return "Synchronization complete for " + result.scopeKey() + ": "
				+ result.changeCount() + " changes processed";
	}

	private void loadQuota(ComboBox<WorkspaceUser> userPicker, Label status,
			ListView<String> details, Button refreshButton) {
		String selectedUserEmail = selectedUserEmailFor(userPicker, previewUserEmail);
		if (serviceAccountUseCase == null || driveUsageQuotaUseCase == null
				|| selectedUserEmail == null || selectedUserEmail.isBlank()) {
			status.setText("Drive storage usage unavailable. Configure service-account access.");
			details.getItems().clear();
			details.setVisible(false);
			details.setManaged(false);
			refreshButton.setVisible(false);
			refreshButton.setManaged(false);
			return;
		}
		status.setText("Loading Drive storage usage for " + selectedUserEmail + "...");
		refreshButton.setDisable(true);
		CompletableFuture.supplyAsync(() -> {
			ServiceAccountAccess access = serviceAccountUseCase.authenticateAs(selectedUserEmail);
			return driveUsageQuotaUseCase.getUsageQuota(access);
		}).whenComplete((quota, error) -> Platform.runLater(() -> {
			refreshButton.setDisable(false);
			if (error != null) {
				status.setText("Drive storage usage unavailable: " + messageFor(error));
				details.getItems().clear();
				details.setVisible(false);
				details.setManaged(false);
				refreshButton.setVisible(true);
				refreshButton.setManaged(true);
				return;
			}
			details.getItems().setAll(formatQuota(quota));
			details.setVisible(true);
			details.setManaged(true);
			refreshButton.setVisible(true);
			refreshButton.setManaged(true);
			status.setText("Drive storage usage for " + quota.userEmail());
		}));
	}

	private static List<String> formatQuota(DriveUsageQuota quota) {
		return List.of(
				"Used: " + formatBytes(quota.usageBytes()),
				"Limit: " + formatBytes(quota.limitBytes()),
				"Drive: " + formatBytes(quota.driveUsageBytes()),
				"Trash: " + formatBytes(quota.trashUsageBytes()));
	}

	private static String formatBytes(Long bytes) {
		if (bytes == null) {
			return "Not reported";
		}
		if (bytes < 1024 * 1024) {
			return bytes + " bytes";
		}
		return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
	}

	private void loadCloudQuota(Label status, ListView<String> details, Button refreshButton) {
		if (cloudQuotaLimitUseCase == null) {
			status.setText("Cloud API quota limits unavailable. Configure project quota access.");
			details.getItems().clear();
			details.setVisible(false);
			details.setManaged(false);
			refreshButton.setVisible(false);
			refreshButton.setManaged(false);
			return;
		}
		status.setText("Loading Cloud API quota limits...");
		refreshButton.setDisable(true);
		CompletableFuture.supplyAsync(cloudQuotaLimitUseCase::listQuotaLimits)
				.whenComplete((limits, error) -> Platform.runLater(() -> {
					refreshButton.setDisable(false);
					if (error != null) {
						status.setText("Cloud API quota limits unavailable: " + messageFor(error));
						details.getItems().clear();
						details.setVisible(false);
						details.setManaged(false);
						refreshButton.setVisible(true);
						refreshButton.setManaged(true);
						return;
					}
					details.getItems().setAll(limits.stream().map(JavaFxApplication::formatCloudLimit).toList());
					details.setVisible(true);
					details.setManaged(true);
					refreshButton.setVisible(true);
					refreshButton.setManaged(true);
					status.setText("Cloud API quota limits from the configured project");
				}));
	}

	private static String formatCloudLimit(CloudQuotaLimit limit) {
		String name = limit.displayName() == null || limit.displayName().isBlank()
				? limit.metric() : limit.displayName();
		return limit.service() + " | " + name + " | default: "
				+ valueOrNotReported(limit.defaultLimit()) + " | max: " + valueOrNotReported(limit.maxLimit())
				+ " | unit: " + valueOrNotReported(limit.unit());
	}

	private static String valueOrNotReported(Object value) {
		return value == null ? "Not reported" : value.toString();
	}

	private void loadWorkspaceReport(ComboBox<WorkspaceUser> userPicker, Label status,
			ListView<String> details, Button refreshButton) {
		String selectedUserEmail = selectedUserEmailFor(userPicker, previewUserEmail);
		if (serviceAccountUseCase == null || workspaceUsageReportUseCase == null
				|| selectedUserEmail == null || selectedUserEmail.isBlank()) {
			status.setText("Workspace usage report unavailable. Authorize the Reports scope.");
			details.getItems().clear();
			details.setVisible(false);
			details.setManaged(false);
			refreshButton.setVisible(false);
			refreshButton.setManaged(false);
			return;
		}
		status.setText("Loading Workspace usage report...");
		refreshButton.setDisable(true);
		CompletableFuture.supplyAsync(() -> {
			ServiceAccountAccess access = serviceAccountUseCase.authenticateAs(selectedUserEmail);
			return workspaceUsageReportUseCase.getLatestReport(access);
		}).whenComplete((report, error) -> Platform.runLater(() -> {
			refreshButton.setDisable(false);
			if (error != null) {
				status.setText("Workspace usage report unavailable: " + messageFor(error));
				details.getItems().clear();
				details.setVisible(false);
				details.setManaged(false);
				refreshButton.setVisible(true);
				refreshButton.setManaged(true);
				return;
			}
			details.getItems().setAll(report.metrics().stream()
					.map(metric -> metric.name() + ": " + metric.value())
					.toList());
			details.setVisible(true);
			details.setManaged(true);
			refreshButton.setVisible(true);
			refreshButton.setManaged(true);
			status.setText("Workspace usage report for " + report.date() + " (latest available)");
		}));
	}

	private void loadDrives(ListView<AvailableDrive> drives, Label status, ComboBox<WorkspaceUser> userPicker,
			ListView<DriveItem> driveItems) {
		String selectedUserEmail = selectedUserEmailFor(userPicker, previewUserEmail);
		if (serviceAccountUseCase == null || driveReadPort == null || selectedUserEmail == null
				|| selectedUserEmail.isBlank()) {
			driveItems.getItems().clear();
			driveItems.setVisible(false);
			driveItems.setManaged(false);
			status.setText("Google connected, Drive preview unavailable. Configure GOOGLE_IMPERSONATED_USER.");
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
			drives.getItems().setAll(availableDrives);
			drives.setVisible(true);
			drives.setManaged(true);
			driveItems.getItems().clear();
			driveItems.setVisible(false);
			driveItems.setManaged(false);
			status.setText("Available drives for " + selectedUserEmail);
		}));
	}

	private void loadDriveContents(String userEmail, AvailableDrive selectedDrive, ListView<DriveItem> driveItems,
			Label status) {
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

	private void loadFolderContents(String userEmail, DriveItem selectedItem, ListView<DriveItem> driveItems,
			Label status) {
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

	private static String selectedUserEmailFor(ComboBox<WorkspaceUser> userPicker, String fallbackEmail) {
		if (userPicker != null && userPicker.getValue() != null) {
			return userPicker.getValue().email();
		}
		return fallbackEmail;
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

	@Override
	public void stop() {
		if (springContext != null) {
			springContext.close();
		}
	}

}