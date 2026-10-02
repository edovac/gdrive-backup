package org.nm.gdrive_backup.adapter.in.javafx;

import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.nm.gdrive_backup.domain.model.DownloadFailure;
import org.nm.gdrive_backup.domain.port.in.DownloadFailureReportUseCase;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;

/**
 * The Failed files view: the files backups skipped because Drive would not give their content, and that are still
 * not backed up. Each later backup tries them again; a file leaves the list once it is backed up or is gone from
 * Drive. The list comes from the database, so it survives restarts. Loading runs off the FX thread.
 */
public final class FailedFilesPanel {

	private final DownloadFailureReportUseCase reportUseCase;

	private final Label heading = new Label();
	private final TableView<DownloadFailure> table = new TableView<>();
	private final Button refreshButton = new Button("Refresh");
	private final Label status = new Label();
	private final VBox root;

	public FailedFilesPanel(DownloadFailureReportUseCase reportUseCase) {
		this.reportUseCase = reportUseCase;

		Label title = new Label("Failed files");
		title.getStyleClass().add("subtitle");
		Label note = new Label("Files a backup skipped because their content could not be downloaded. Every later "
				+ "backup tries them again.");
		note.getStyleClass().add("scope");
		note.setWrapText(true);
		note.setMaxWidth(540);

		heading.getStyleClass().add("scope");
		refreshButton.setOnAction(event -> refresh());

		table.setPlaceholder(new Label("No failed files"));
		table.setPrefHeight(330);
		table.setMaxWidth(540);
		table.getColumns().add(column("File", 190, DownloadFailure::drivePath));
		table.getColumns().add(column("Reason", 220, DownloadFailure::reason));
		table.getColumns().add(column("Last tried", 110, failure -> FailedFilesText.when(failure, ZoneId.systemDefault())));

		status.getStyleClass().add("status");
		status.setWrapText(true);
		status.setMaxWidth(540);

		root = new VBox(6, title, note, heading, table, refreshButton, status);
		root.setAlignment(Pos.CENTER);
		hide();
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
		heading.setText("");
		status.setText("");
	}

	/** Reloads the list; also called after a backup, which is what changes it. */
	public void refresh() {
		if (reportUseCase == null) {
			return;
		}
		refreshButton.setDisable(true);
		CompletableFuture.supplyAsync(reportUseCase::openFailures)
				.whenComplete((failures, error) -> Platform.runLater(() -> {
					refreshButton.setDisable(false);
					if (error != null) {
						status.setText("Could not load the failed files: " + rootMessage(error));
						return;
					}
					table.getItems().setAll(failures);
					heading.setText(FailedFilesText.heading(failures.size()));
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

	private static TableColumn<DownloadFailure, String> column(String title, double width,
			Function<DownloadFailure, String> text) {
		TableColumn<DownloadFailure, String> column = new TableColumn<>(title);
		column.setPrefWidth(width);
		column.setSortable(false);
		column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(text.apply(cell.getValue())));
		return column;
	}
}
