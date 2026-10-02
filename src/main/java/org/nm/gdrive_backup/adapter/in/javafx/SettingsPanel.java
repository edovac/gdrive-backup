package org.nm.gdrive_backup.adapter.in.javafx;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import org.nm.gdrive_backup.domain.model.CredentialConfiguration;
import org.nm.gdrive_backup.domain.port.in.CredentialConfigurationUseCase;
import org.nm.gdrive_backup.domain.port.in.DownloadConcurrencyUseCase;

/**
 * Lets the admin import or clear the Google credentials the app uses (the service-account key and
 * the OAuth client secrets), set the Cloud project id, replacing manual env var/file setup, and choose how many
 * files a backup downloads at once for the session.
 */
public final class SettingsPanel {

	private final CredentialConfigurationUseCase useCase;
	/** Null when no download-concurrency use case is wired; the section is then left out. */
	private final DownloadConcurrencyUseCase downloadConcurrency;

	private final Label serviceAccountStatus = new Label();
	private final Button importServiceAccountKey = new Button("Import file...");
	private final Button clearServiceAccountKey = new Button("Clear");

	private final Label oauthStatus = new Label();
	private final Button importOAuthClientSecrets = new Button("Import file...");
	private final Button clearOAuthClientSecrets = new Button("Clear");

	private final TextField projectIdField = new TextField();
	private final Button saveProjectId = new Button("Save");

	private final Spinner<Integer> downloadConcurrencyField = new Spinner<>();
	private final Button saveDownloadConcurrency = new Button("Save");

	private final Label message = new Label();
	private final VBox root;

	public SettingsPanel(CredentialConfigurationUseCase useCase, DownloadConcurrencyUseCase downloadConcurrency) {
		this.useCase = useCase;
		this.downloadConcurrency = downloadConcurrency;

		Label title = new Label("Settings");
		title.getStyleClass().add("subtitle");

		importServiceAccountKey.getStyleClass().add("secondary-button");
		clearServiceAccountKey.getStyleClass().add("secondary-button");
		importServiceAccountKey.setOnAction(event -> chooseFile(
				"Import service-account key", useCase::importServiceAccountKey, "Service-account key"));
		clearServiceAccountKey.setOnAction(event -> clear(useCase::clearServiceAccountKey, "Service-account key"));
		VBox serviceAccountSection = new VBox(4,
				new Label("Service-account key"), serviceAccountStatus,
				new HBox(8, importServiceAccountKey, clearServiceAccountKey));

		importOAuthClientSecrets.getStyleClass().add("secondary-button");
		clearOAuthClientSecrets.getStyleClass().add("secondary-button");
		importOAuthClientSecrets.setOnAction(event -> chooseFile(
				"Import OAuth client secrets", useCase::importOAuthClientSecrets, "OAuth client secrets"));
		clearOAuthClientSecrets.setOnAction(event -> clear(useCase::clearOAuthClientSecrets, "OAuth client secrets"));
		VBox oauthSection = new VBox(4,
				new Label("OAuth client secrets"), oauthStatus,
				new HBox(8, importOAuthClientSecrets, clearOAuthClientSecrets));

		projectIdField.setPromptText("Google Cloud project id");
		saveProjectId.getStyleClass().add("secondary-button");
		saveProjectId.setOnAction(event -> saveProjectId());
		VBox projectIdSection = new VBox(4,
				new Label("Cloud project id"), new HBox(8, projectIdField, saveProjectId));

		message.getStyleClass().add("status");
		message.setWrapText(true);
		message.setMaxWidth(440);

		root = new VBox(16, title, serviceAccountSection, oauthSection, projectIdSection);
		if (downloadConcurrency != null) {
			downloadConcurrencyField.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
					downloadConcurrency.minimum(), downloadConcurrency.maximum(), downloadConcurrency.current()));
			downloadConcurrencyField.setPrefWidth(90);
			saveDownloadConcurrency.getStyleClass().add("secondary-button");
			saveDownloadConcurrency.setOnAction(event -> saveDownloadConcurrency());
			Label hint = new Label(SettingsText.downloadConcurrencyHint(
					downloadConcurrency.minimum(), downloadConcurrency.maximum()));
			hint.getStyleClass().add("scope");
			hint.setWrapText(true);
			hint.setMaxWidth(440);
			root.getChildren().add(new VBox(4, new Label("Parallel downloads"), hint,
					new HBox(8, downloadConcurrencyField, saveDownloadConcurrency)));
		}
		root.getChildren().add(message);
		root.setPadding(new Insets(16));
		root.setMaxWidth(440);
		root.setAlignment(Pos.TOP_LEFT);

		refresh();
	}

	public Node node() {
		return root;
	}

	public void refresh() {
		CredentialConfiguration configuration = useCase.currentConfiguration();
		serviceAccountStatus.setText(SettingsText.serviceAccountKeyStatus(configuration.serviceAccountKeyConfigured()));
		oauthStatus.setText(SettingsText.oauthClientSecretsStatus(configuration.oauthClientSecretsConfigured()));
		projectIdField.setText(configuration.projectId() == null ? "" : configuration.projectId());
		refreshDownloadConcurrency();
		message.setText("");
	}

	private void refreshDownloadConcurrency() {
		if (downloadConcurrency != null) {
			downloadConcurrencyField.getValueFactory().setValue(downloadConcurrency.current());
		}
	}

	private void saveDownloadConcurrency() {
		int value = downloadConcurrencyField.getValue();
		setBusy(true);
		CompletableFuture.runAsync(() -> downloadConcurrency.change(value))
				.whenComplete((ignored, error) -> Platform.runLater(() -> {
					setBusy(false);
					refreshDownloadConcurrency();
					message.setText(error != null
							? SettingsText.downloadConcurrencySaveFailed(SettingsText.reason(error))
							: SettingsText.downloadConcurrencySaved(value));
				}));
	}

	private void chooseFile(String title, Consumer<Path> importFile, String label) {
		FileChooser chooser = new FileChooser();
		chooser.setTitle(title);
		chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON files", "*.json"));
		File selected = chooser.showOpenDialog(root.getScene().getWindow());
		if (selected == null) {
			return;
		}
		setBusy(true);
		message.setText("Importing " + label + "...");
		CompletableFuture.runAsync(() -> importFile.accept(selected.toPath()))
				.whenComplete((ignored, error) -> Platform.runLater(() -> {
					setBusy(false);
					if (error != null) {
						message.setText(SettingsText.importFailed(label, SettingsText.reason(error)));
						return;
					}
					refresh();
					message.setText(SettingsText.importSucceeded(label));
				}));
	}

	private void clear(Runnable clearAction, String label) {
		setBusy(true);
		CompletableFuture.runAsync(clearAction)
				.whenComplete((ignored, error) -> Platform.runLater(() -> {
					setBusy(false);
					if (error != null) {
						message.setText(SettingsText.clearFailed(label, SettingsText.reason(error)));
						return;
					}
					refresh();
					message.setText(SettingsText.cleared(label));
				}));
	}

	private void saveProjectId() {
		String value = projectIdField.getText();
		setBusy(true);
		CompletableFuture.runAsync(() -> useCase.updateProjectId(value))
				.whenComplete((ignored, error) -> Platform.runLater(() -> {
					setBusy(false);
					if (error != null) {
						message.setText(SettingsText.projectIdSaveFailed(SettingsText.reason(error)));
						return;
					}
					refresh();
					message.setText(SettingsText.projectIdSaved());
				}));
	}

	private void setBusy(boolean busy) {
		importServiceAccountKey.setDisable(busy);
		clearServiceAccountKey.setDisable(busy);
		importOAuthClientSecrets.setDisable(busy);
		clearOAuthClientSecrets.setDisable(busy);
		saveProjectId.setDisable(busy);
		saveDownloadConcurrency.setDisable(busy);
		downloadConcurrencyField.setDisable(busy);
	}
}
