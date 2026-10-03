package org.nm.gdrive_backup.adapter.in.javafx;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.nm.gdrive_backup.domain.model.ArchiveState;
import org.nm.gdrive_backup.domain.model.ArchiveView;
import org.nm.gdrive_backup.domain.model.DatabaseRebuildResult;
import org.nm.gdrive_backup.domain.model.DeletionPlan;
import org.nm.gdrive_backup.domain.model.DeletionResult;
import org.nm.gdrive_backup.domain.model.MergeResult;
import org.nm.gdrive_backup.domain.model.ScopeArchives;
import org.nm.gdrive_backup.domain.port.in.ArchiveCatalogUseCase;
import org.nm.gdrive_backup.domain.port.in.ArchiveDeletionUseCase;
import org.nm.gdrive_backup.domain.port.in.ArchiveMergeUseCase;
import org.nm.gdrive_backup.domain.port.in.DatabaseRebuildUseCase;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;

/**
 * The Archive manager: lists each drive's archive chain, merges it into a new full backup and deletes the archives
 * a merge made obsolete. Long operations run off the FX thread and report progress through the application's
 * progress panel, which the owner starts and stops through the two hooks.
 */
public final class ArchiveManagerPanel {

	private final ArchiveCatalogUseCase catalogUseCase;
	private final ArchiveMergeUseCase mergeUseCase;
	private final ArchiveDeletionUseCase deletionUseCase;
	private final DatabaseRebuildUseCase rebuildUseCase;
	private final Runnable onOperationStarted;
	private final Runnable onOperationFinished;

	private final ComboBox<ScopeArchives> scopePicker = new ComboBox<>();
	private final TableView<ArchiveView> table = new TableView<>();
	private final Label warnings = new Label();
	private final Label status = new Label();
	private final Button rebuildButton = new Button("Rebuild database...");
	private final Button refreshButton = new Button("Refresh");
	private final Button mergeButton = new Button("Merge into a full backup...");
	private final Button deleteButton = new Button("Delete obsolete archives...");
	private final VBox root;

	private boolean operationRunning;
	private boolean externallyBusy;

	public ArchiveManagerPanel(ArchiveCatalogUseCase catalogUseCase, ArchiveMergeUseCase mergeUseCase,
			ArchiveDeletionUseCase deletionUseCase, DatabaseRebuildUseCase rebuildUseCase, Runnable onOperationStarted,
			Runnable onOperationFinished) {
		this.catalogUseCase = catalogUseCase;
		this.mergeUseCase = mergeUseCase;
		this.deletionUseCase = deletionUseCase;
		this.rebuildUseCase = rebuildUseCase;
		this.onOperationStarted = onOperationStarted;
		this.onOperationFinished = onOperationFinished;

		Label title = new Label("Archives");
		title.getStyleClass().add("subtitle");
		Label note = new Label("Merging builds one full backup from a drive's archives without contacting Google.");
		note.getStyleClass().add("scope");
		note.setWrapText(true);
		note.setMaxWidth(540);

		scopePicker.setPromptText("Select a drive");
		scopePicker.setMaxWidth(Double.MAX_VALUE);
		scopePicker.setCellFactory(view -> scopeCell());
		scopePicker.setButtonCell(scopeCell());
		scopePicker.setOnAction(event -> showSelectedScope());

		configureTable();
		warnings.getStyleClass().add("status");
		warnings.setWrapText(true);
		warnings.setMaxWidth(540);
		warnings.setStyle("-fx-text-fill: #b3261e;");
		status.getStyleClass().add("status");
		status.setWrapText(true);
		status.setMaxWidth(540);
		refreshButton.getStyleClass().add("secondary-button");
		refreshButton.setOnAction(event -> refresh());
		mergeButton.getStyleClass().add("primary-button");
		mergeButton.setOnAction(event -> confirmAndMerge());
		deleteButton.getStyleClass().add("secondary-button");
		deleteButton.setOnAction(event -> prepareDeletion());

		rebuildButton.getStyleClass().add("secondary-button");
		rebuildButton.setOnAction(event -> confirmAndRebuild());

		VBox buttons = new VBox(6, mergeButton, deleteButton, rebuildButton, refreshButton);
		buttons.setAlignment(Pos.CENTER);
		root = new VBox(6, title, note, scopePicker, table, warnings, buttons, status);
		root.setAlignment(Pos.CENTER);
		hide();
		updateButtons();
	}

	public Node node() {
		return root;
	}

	public void show() {
		root.setVisible(true);
		root.setManaged(true);
		refresh();
	}

	public void hide() {
		root.setVisible(false);
		root.setManaged(false);
		table.getItems().clear();
		scopePicker.getItems().clear();
		warnings.setText("");
		status.setText("");
	}

	/** Disabled while something else (a backup) uses the shared progress panel and the archives. */
	public void setExternallyBusy(boolean busy) {
		externallyBusy = busy;
		updateButtons();
	}

	private void configureTable() {
		table.setPlaceholder(new Label("No archives yet"));
		table.setPrefHeight(190);
		table.setMaxWidth(540);
		table.getColumns().add(column("#", 36, view -> Integer.toString(view.archive().sequenceNumber())));
		table.getColumns().add(column("Kind", 95, view -> ArchiveManagerText.kind(view.archive().mode())));
		table.getColumns().add(column("Created", 145,
				view -> ArchiveManagerText.created(view.archive().createdAt(), ZoneId.systemDefault())));
		table.getColumns().add(column("Size", 75, view -> ArchiveManagerText.size(view.sizeBytes())));
		table.getColumns().add(column("State", 165, ArchiveManagerText::state));
		table.setRowFactory(view -> new TableRow<>() {
			@Override
			protected void updateItem(ArchiveView item, boolean empty) {
				super.updateItem(item, empty);
				if (empty || item == null) {
					setStyle("");
				} else if (item.fileMissing()) {
					setStyle("-fx-text-fill: #b3261e; -fx-font-weight: bold;");
				} else if (item.state() == ArchiveState.OBSOLETE || item.state() == ArchiveState.PREVIOUS_CHAIN) {
					setStyle("-fx-opacity: 0.6;");
				} else {
					setStyle("");
				}
			}
		});
	}

	private static TableColumn<ArchiveView, String> column(String title, double width,
			java.util.function.Function<ArchiveView, String> text) {
		TableColumn<ArchiveView, String> column = new TableColumn<>(title);
		column.setPrefWidth(width);
		column.setSortable(false);
		column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(text.apply(cell.getValue())));
		return column;
	}

	private static ListCell<ScopeArchives> scopeCell() {
		return new ListCell<>() {
			@Override
			protected void updateItem(ScopeArchives item, boolean empty) {
				super.updateItem(item, empty);
				setText(empty || item == null ? null : item.label());
			}
		};
	}

	private void refresh() {
		ScopeArchives previous = scopePicker.getValue();
		status.setText("Loading archives...");
		CompletableFuture.supplyAsync(catalogUseCase::listScopes).whenComplete((scopes, error) -> Platform.runLater(() -> {
			if (error != null) {
				status.setText("Unable to list archives: " + messageFor(error));
				return;
			}
			scopePicker.getItems().setAll(scopes);
			ScopeArchives reselect = previous == null ? null : scopes.stream()
					.filter(scope -> scope.scope().equals(previous.scope())).findFirst().orElse(null);
			scopePicker.setValue(reselect != null ? reselect : scopes.isEmpty() ? null : scopes.getFirst());
			showSelectedScope();
			status.setText(scopes.isEmpty() ? "No archives have been written yet." : "");
		}));
	}

	private void showSelectedScope() {
		ScopeArchives selected = scopePicker.getValue();
		table.getItems().setAll(selected == null ? List.of() : selected.archives());
		warnings.setText(selected == null ? "" : ArchiveManagerText.warnings(selected.warnings()));
		updateButtons();
	}

	private void updateButtons() {
		ScopeArchives selected = scopePicker.getValue();
		boolean busy = operationRunning || externallyBusy;
		mergeButton.setDisable(busy || selected == null || !selected.canMerge());
		deleteButton.setDisable(busy || selected == null || !selected.hasObsolete());
		rebuildButton.setDisable(busy);
		refreshButton.setDisable(operationRunning);
		scopePicker.setDisable(operationRunning);
	}

	/** Reachable with no archives listed, since a missing database is exactly when the list is empty. */
	private void confirmAndRebuild() {
		status.setText("Checking the database...");
		CompletableFuture.supplyAsync(rebuildUseCase::status).whenComplete((databaseStatus, error) -> Platform.runLater(() -> {
			if (error != null) {
				status.setText("Failed: " + messageFor(error));
				return;
			}
			if (!confirm("Rebuild database", "Rebuild the database from the archives?",
					ArchiveManagerText.rebuildConfirmation(databaseStatus), "Rebuild")) {
				status.setText("");
				return;
			}
			status.setText("Rebuilding the database...");
			run(() -> rebuildUseCase.rebuild(true), (DatabaseRebuildResult result) -> {
				status.setText(result.cancelled() ? ArchiveManagerText.rebuildResult(result) : "Database rebuilt.");
				if (!result.cancelled()) {
					showText(result.problems().isEmpty() ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING,
							"Rebuild database", "Database rebuilt", ArchiveManagerText.rebuildResult(result));
				}
				refresh();
			});
		}));
	}

	private void confirmAndMerge() {
		ScopeArchives selected = scopePicker.getValue();
		if (selected == null || !confirm("Merge archives", "Merge into a full backup?",
				ArchiveManagerText.mergeConfirmation(selected), "Merge")) {
			return;
		}
		status.setText("Merging " + selected.label() + "...");
		run(() -> mergeUseCase.merge(selected.scope(),
				ArchiveManagerText.displayName(selected.label(), selected.scope().key())),
				(MergeResult result) -> {
					status.setText(ArchiveManagerText.mergeResult(result));
					refresh();
					if (!result.cancelled()) {
						offerDeletionAfterMerge();
					}
				});
	}

	private void offerDeletionAfterMerge() {
		if (confirm("Delete obsolete archives", "Delete the archives this merge replaced?",
				"The merged full now carries their content. Deleting them frees space, but first the merged full is "
						+ "verified and you will see exactly what would be removed before anything is deleted.",
				"Review deletion...")) {
			prepareDeletion();
		}
	}

	private void prepareDeletion() {
		ScopeArchives selected = scopePicker.getValue();
		if (selected == null) {
			return;
		}
		status.setText("Verifying the merged archive...");
		run(() -> deletionUseCase.prepare(selected.scope(),
				ArchiveManagerText.displayName(selected.label(), selected.scope().key())),
				(Optional<DeletionPlan> plan) -> {
					if (plan.isEmpty()) {
						status.setText("Verification cancelled. Nothing was deleted.");
						return;
					}
					reviewDeletion(selected, plan.get());
				});
	}

	private void reviewDeletion(ScopeArchives selected, DeletionPlan plan) {
		String summary = ArchiveManagerText.deletionSummary(plan);
		if (!plan.verified()) {
			showText(Alert.AlertType.ERROR, "Delete obsolete archives", "The merged archive did not verify", summary);
			status.setText("Nothing was deleted: verification failed.");
			return;
		}
		if (!confirmText("Delete obsolete archives", "Delete these archives permanently?", summary, "Delete")) {
			status.setText("Nothing was deleted.");
			return;
		}
		status.setText("Deleting obsolete archives...");
		run(() -> deletionUseCase.execute(selected.scope(), plan), (DeletionResult result) -> {
			status.setText(ArchiveManagerText.deletionResult(result));
			refresh();
		});
	}

	/** Runs {@code work} off the FX thread with the progress panel showing, then hands the result back on it. */
	private <T> void run(Supplier<T> work, Consumer<T> onSuccess) {
		operationRunning = true;
		updateButtons();
		onOperationStarted.run();
		CompletableFuture.supplyAsync(work).whenComplete((result, error) -> Platform.runLater(() -> {
			operationRunning = false;
			onOperationFinished.run();
			updateButtons();
			if (error != null) {
				status.setText("Failed: " + messageFor(error));
				return;
			}
			onSuccess.accept(result);
		}));
	}

	private static boolean confirm(String title, String header, String content, String confirmLabel) {
		Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
		alert.setTitle(title);
		alert.setHeaderText(header);
		alert.setContentText(content);
		alert.getDialogPane().setMinWidth(520);
		ButtonType confirmButton = new ButtonType(confirmLabel, javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
		ButtonType cancel = new ButtonType("Cancel", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
		alert.getButtonTypes().setAll(confirmButton, cancel);
		return alert.showAndWait().orElse(cancel) == confirmButton;
	}

	private static boolean confirmText(String title, String header, String text, String confirmLabel) {
		Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
		alert.setTitle(title);
		alert.setHeaderText(header);
		alert.getDialogPane().setContent(scrollableText(text));
		alert.getDialogPane().setMinWidth(640);
		ButtonType confirmButton = new ButtonType(confirmLabel, javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
		ButtonType cancel = new ButtonType("Cancel", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
		alert.getButtonTypes().setAll(confirmButton, cancel);
		return alert.showAndWait().orElse(cancel) == confirmButton;
	}

	private static void showText(Alert.AlertType type, String title, String header, String text) {
		Alert alert = new Alert(type);
		alert.setTitle(title);
		alert.setHeaderText(header);
		alert.getDialogPane().setContent(scrollableText(text));
		alert.getDialogPane().setMinWidth(640);
		alert.showAndWait();
	}

	private static TextArea scrollableText(String text) {
		TextArea area = new TextArea(text);
		area.setEditable(false);
		area.setWrapText(true);
		area.setPrefRowCount(16);
		area.setPrefColumnCount(70);
		return area;
	}

	private static String messageFor(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
	}
}
