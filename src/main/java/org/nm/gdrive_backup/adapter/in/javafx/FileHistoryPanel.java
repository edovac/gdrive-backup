package org.nm.gdrive_backup.adapter.in.javafx;

import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.nm.gdrive_backup.domain.model.FileHistory;
import org.nm.gdrive_backup.domain.model.HistoryEntry;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.port.in.FileHistoryUseCase;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * The History view: search the backed-up files by name, pick one and see its renames, moves, trashing and captured
 * revisions in time order, each with the archive that recorded it. Searches run off the FX thread.
 */
public final class FileHistoryPanel {

	private final FileHistoryUseCase historyUseCase;

	private final TextField searchField = new TextField();
	private final Button searchButton = new Button("Search");
	private final TableView<StoredFile> results = new TableView<>();
	private final TableView<HistoryEntry> history = new TableView<>();
	private final Label status = new Label();
	private final VBox root;

	public FileHistoryPanel(FileHistoryUseCase historyUseCase) {
		this.historyUseCase = historyUseCase;

		Label title = new Label("File history");
		title.getStyleClass().add("subtitle");
		Label note = new Label("Search the backed-up files by name to see what happened to a file and which archive "
				+ "holds each version.");
		note.getStyleClass().add("scope");
		note.setWrapText(true);
		note.setMaxWidth(540);

		searchField.setPromptText("File name");
		searchField.setOnAction(event -> search());
		searchButton.getStyleClass().add("primary-button");
		searchButton.setOnAction(event -> search());
		HBox searchRow = new HBox(6, searchField, searchButton);
		searchRow.setAlignment(Pos.CENTER);
		HBox.setHgrow(searchField, javafx.scene.layout.Priority.ALWAYS);
		searchRow.setMaxWidth(540);

		results.setPlaceholder(new Label("Type a file name and search"));
		results.setPrefHeight(150);
		results.setMaxWidth(540);
		results.getColumns().add(column("Name", 260, StoredFile::name));
		results.getColumns().add(column("Drive", 190, StoredFile::ownerScope));
		results.getColumns().add(column("", 70, FileHistoryText::status));
		results.getSelectionModel().selectedItemProperty()
				.addListener((observable, previous, file) -> showHistory(file));

		history.setPlaceholder(new Label("Select a file to see its history"));
		history.setPrefHeight(230);
		history.setMaxWidth(540);
		history.getColumns().add(historyColumn("When", 125,
				entry -> FileHistoryText.when(entry.timestamp(), ZoneId.systemDefault())));
		history.getColumns().add(historyColumn("What", 270, FileHistoryText::what));
		history.getColumns().add(historyColumn("Archive", 110, FileHistoryText::archive));

		status.getStyleClass().add("status");
		status.setWrapText(true);
		status.setMaxWidth(540);

		root = new VBox(6, title, note, searchRow, results, history, status);
		root.setAlignment(Pos.CENTER);
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
		results.getItems().clear();
		history.getItems().clear();
		searchField.clear();
		status.setText("");
	}

	private void search() {
		String query = searchField.getText();
		if (historyUseCase == null || query == null || query.isBlank()) {
			status.setText("Enter part of a file name to search.");
			return;
		}
		searchButton.setDisable(true);
		history.getItems().clear();
		status.setText("Searching...");
		CompletableFuture.supplyAsync(() -> historyUseCase.searchFiles(query))
				.whenComplete((files, error) -> Platform.runLater(() -> {
					searchButton.setDisable(false);
					if (error != null) {
						status.setText("Search failed: " + rootMessage(error));
						return;
					}
					results.getItems().setAll(files);
					results.setPlaceholder(new Label("No files match"));
					status.setText(files.isEmpty() ? "" : files.size() + " file(s) found.");
				}));
	}

	private void showHistory(StoredFile file) {
		history.getItems().clear();
		if (file == null || historyUseCase == null) {
			return;
		}
		status.setText("Loading history...");
		CompletableFuture.supplyAsync(() -> historyUseCase.historyOf(file.fileId()))
				.whenComplete((loaded, error) -> Platform.runLater(() -> {
					if (error != null) {
						status.setText("Could not load the history: " + rootMessage(error));
						return;
					}
					List<HistoryEntry> entries = loaded.map(FileHistory::entries).orElse(List.of());
					history.getItems().setAll(entries);
					history.setPlaceholder(new Label("No history recorded for this file"));
					status.setText("");
				}));
	}

	private static String rootMessage(Throwable error) {
		Throwable cause = error;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
	}

	private static TableColumn<StoredFile, String> column(String title, double width,
			Function<StoredFile, String> text) {
		TableColumn<StoredFile, String> column = new TableColumn<>(title);
		column.setPrefWidth(width);
		column.setSortable(false);
		column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(text.apply(cell.getValue())));
		return column;
	}

	private static TableColumn<HistoryEntry, String> historyColumn(String title, double width,
			Function<HistoryEntry, String> text) {
		TableColumn<HistoryEntry, String> column = new TableColumn<>(title);
		column.setPrefWidth(width);
		column.setSortable(false);
		column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(text.apply(cell.getValue())));
		return column;
	}
}
