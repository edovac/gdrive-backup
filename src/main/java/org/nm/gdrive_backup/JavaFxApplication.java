package org.nm.gdrive_backup;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.springframework.context.ConfigurableApplicationContext;
import org.nm.gdrive_backup.domain.model.GoogleLoginSession;
import org.nm.gdrive_backup.domain.port.in.GoogleLoginUseCase;
import org.nm.gdrive_backup.domain.port.in.ServiceAccountAuthenticationUseCase;
import org.nm.gdrive_backup.domain.port.in.WorkspaceUserListingUseCase;
import org.nm.gdrive_backup.domain.port.out.DriveReadPort;
import org.nm.gdrive_backup.domain.model.AvailableDrive;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.WorkspaceUser;

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
	private static DriveReadPort driveReadPort;
	private static String previewUserEmail;

	static void setSpringContext(ConfigurableApplicationContext context) {
		springContext = context;
	}

	static void setLoginUseCase(GoogleLoginUseCase useCase) {
		loginUseCase = useCase;
	}

	static void setDriveServices(ServiceAccountAuthenticationUseCase authenticationUseCase,
			DriveReadPort readPort, WorkspaceUserListingUseCase workspaceUserUseCase, String userEmail) {
		serviceAccountUseCase = authenticationUseCase;
		driveReadPort = readPort;
		workspaceUserListingUseCase = workspaceUserUseCase;
		previewUserEmail = userEmail;
	}

	@Override
	public void start(Stage stage) {
		Button minimize = new Button("-");
		Button maximize = new Button("+");
		Button close = new Button("x");

		minimize.setOnAction(event -> stage.setIconified(true));
		maximize.setOnAction(event -> stage.setMaximized(!stage.isMaximized()));
		close.setOnAction(event -> Platform.exit());

		HBox controls = new HBox(6, minimize, maximize, close);
		controls.setAlignment(Pos.TOP_RIGHT);
		controls.getStyleClass().add("window-controls");

		BorderPane root = new BorderPane();
		root.setTop(controls);
		root.setCenter(loginView());
		root.setStyle("-fx-background-color: #f7f8fa;");
		Scene scene = new Scene(root, 640, 400);
		scene.getStylesheets().add("/login.css");
		stage.initStyle(StageStyle.UNDECORATED);
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
		signOut.getStyleClass().add("secondary-button");
		signOut.setVisible(false);
		signOut.setManaged(false);

		signIn.setOnAction(event -> authenticate(signIn, signOut, connectionStatus, driveStatus, drives));
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
			drives.getItems().clear();
			drives.setVisible(false);
			drives.setManaged(false);
		});

		VBox content = new VBox(12, title, subtitle, signIn, signOut, scope,
				connectionStatus, driveStatus, drives);
		content.setAlignment(Pos.CENTER);
		content.setMaxWidth(560);
		connectionStatus.setMaxWidth(540);
		driveStatus.setMaxWidth(540);
		connectionStatus.setWrapText(true);
		driveStatus.setWrapText(true);
		return content;
	}

	private void authenticate(Button signIn, Button signOut, Label connectionStatus,
			Label driveStatus, ListView<AvailableDrive> drives) {
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
					loadDrives(drives, driveStatus);
				}));
	}

	private void loadDrives(ListView<AvailableDrive> drives, Label status) {
		if (serviceAccountUseCase == null || driveReadPort == null || previewUserEmail == null
				|| previewUserEmail.isBlank()) {
			status.setText("Google connected, Drive preview unavailable. Configure GOOGLE_IMPERSONATED_USER.");
			return;
		}
		status.setText("Loading available drives...");
		CompletableFuture.supplyAsync(() -> {
			ServiceAccountAccess access = serviceAccountUseCase.authenticateAs(previewUserEmail);
			if (workspaceUserListingUseCase != null) {
				List<WorkspaceUser> users = workspaceUserListingUseCase.listUsers(access);
				System.out.println("Loaded Workspace users: " + users.size());
			}
			return driveReadPort.listAvailableDrives(access);
		}).whenComplete((availableDrives, error) -> Platform.runLater(() -> {
			if (error != null) {
				status.setText("Google connected, Drive preview unavailable: " + messageFor(error));
				return;
			}
			drives.getItems().setAll(availableDrives);
			drives.setVisible(true);
			drives.setManaged(true);
			status.setText("Available drives");
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

	@Override
	public void stop() {
		if (springContext != null) {
			springContext.close();
		}
	}

}