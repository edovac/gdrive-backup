package org.nm.gdrive_backup.adapter.in.javafx;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.nm.gdrive_backup.domain.model.BackupProgress;
import org.nm.gdrive_backup.domain.model.BackupStopMode;
import org.nm.gdrive_backup.domain.port.in.BackupCancellationUseCase;
import org.nm.gdrive_backup.domain.port.out.BackupProgressPort;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Shows live progress for a running operation: current step, drive position, and elapsed/remaining time, with a
 * Cancel button. It polls the shared progress port while started. Each view that runs operations owns its own
 * instance, so an operation's progress appears only where it was started.
 */
public final class OperationProgressPanel {

	private final BackupProgressPort progressPort;
	private final BackupCancellationUseCase cancellationUseCase;
	private final String startingText;

	private final ProgressBar progressBar = new ProgressBar(0);
	private final Label operationLabel = new Label();
	private final Label driveJobLabel = new Label();
	private final Label timeLabel = new Label();
	private final Button cancelButton = new Button("Cancel");
	private final VBox downloadList = new VBox(2);
	private List<OperationProgressText.DownloadRow> shownDownloads = List.of();
	private final Map<String, DownloadRowNodes> downloadRows = new HashMap<>();
	private final VBox root;
	private Timeline timeline;
	private Consumer<BackupProgress> progressListener = progress -> {
	};

	/** @param startingText what the panel says before the first progress snapshot arrives */
	public OperationProgressPanel(BackupProgressPort progressPort, BackupCancellationUseCase cancellationUseCase,
			String startingText) {
		this.progressPort = progressPort;
		this.cancellationUseCase = cancellationUseCase;
		this.startingText = startingText;
		progressBar.setMaxWidth(Double.MAX_VALUE);
		operationLabel.getStyleClass().add("status");
		operationLabel.setWrapText(true);
		driveJobLabel.getStyleClass().add("scope");
		timeLabel.getStyleClass().add("scope");
		cancelButton.getStyleClass().add("secondary-button");
		cancelButton.setOnAction(event -> handleCancelClick());
		downloadList.setFillWidth(true);
		downloadList.setVisible(false);
		downloadList.setManaged(false);
		// Below the Cancel button, so the rows appearing and disappearing never move the controls above them.
		root = new VBox(6, progressBar, operationLabel, driveJobLabel, timeLabel, cancelButton, downloadList);
		root.setAlignment(Pos.CENTER);
		hide();
	}

	public Node node() {
		return root;
	}

	/** Notified with each progress snapshot while the panel is running; pass null to stop listening. */
	public void setProgressListener(Consumer<BackupProgress> listener) {
		this.progressListener = listener == null ? progress -> {
		} : listener;
	}

	public void start() {
		root.setVisible(true);
		root.setManaged(true);
		progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
		operationLabel.setText(startingText);
		driveJobLabel.setText("");
		timeLabel.setText("");
		showDownloads(List.of());
		cancelButton.setDisable(cancellationUseCase == null);
		timeline = new Timeline(new KeyFrame(javafx.util.Duration.millis(250), event -> refresh()));
		timeline.setCycleCount(Animation.INDEFINITE);
		timeline.play();
	}

	public void stop() {
		if (timeline != null) {
			timeline.stop();
			timeline = null;
		}
		hide();
		progressListener = progress -> {
		};
	}

	private void handleCancelClick() {
		if (cancellationUseCase == null) {
			return;
		}
		int totalDrives = progressPort == null ? 1 : progressPort.latest().map(BackupProgress::totalDrives).orElse(1);
		if (totalDrives <= 1) {
			requestStop(BackupStopMode.IMMEDIATE);
			return;
		}
		Alert prompt = new Alert(Alert.AlertType.CONFIRMATION);
		prompt.setTitle("Cancel synchronization");
		prompt.setHeaderText("Stop the running backup?");
		prompt.setContentText("This job covers multiple drives. You can stop right away, "
				+ "or let the drive currently syncing finish first.");
		ButtonType stopNow = new ButtonType("Stop now");
		ButtonType finishCurrent = new ButtonType("Finish current drive, then stop");
		ButtonType keepGoing = new ButtonType("Keep going", ButtonBar.ButtonData.CANCEL_CLOSE);
		prompt.getButtonTypes().setAll(stopNow, finishCurrent, keepGoing);
		prompt.getDialogPane().setMinWidth(520);
		ButtonType choice = prompt.showAndWait().orElse(keepGoing);
		if (choice == stopNow) {
			requestStop(BackupStopMode.IMMEDIATE);
		} else if (choice == finishCurrent) {
			requestStop(BackupStopMode.AFTER_CURRENT_DRIVE);
		}
	}

	private void requestStop(BackupStopMode mode) {
		cancellationUseCase.requestStop(mode);
		cancelButton.setDisable(true);
	}

	private void hide() {
		root.setVisible(false);
		root.setManaged(false);
	}

	private void refresh() {
		if (progressPort == null) {
			return;
		}
		progressPort.latest().ifPresent(progress -> {
			progressBar.setProgress(progress.totalItems() == null
					? ProgressBar.INDETERMINATE_PROGRESS
					: (double) progress.processedItems() / Math.max(1, progress.totalItems()));
			operationLabel.setText(OperationProgressText.operation(progress));
			driveJobLabel.setText(progress.totalDrives() > 1 ? OperationProgressText.multiDrive(progress) : "");
			timeLabel.setText(OperationProgressText.time(progress, Instant.now()));
			showDownloads(OperationProgressText.downloadRows(progress, Instant.now()));
			progressListener.accept(progress);
		});
	}

	/**
	 * Updates the rows in place, by file id. The size changes on almost every refresh, so rebuilding the rows would
	 * remove the name label under the mouse and close its tooltip each time; reused rows keep theirs.
	 */
	private void showDownloads(List<OperationProgressText.DownloadRow> rows) {
		if (rows.equals(shownDownloads)) {
			return;
		}
		shownDownloads = rows;
		Set<String> current = rows.stream().map(OperationProgressText.DownloadRow::fileId)
				.collect(Collectors.toSet());
		downloadRows.entrySet().removeIf(entry -> {
			if (current.contains(entry.getKey())) {
				return false;
			}
			downloadList.getChildren().remove(entry.getValue().box);
			return true;
		});
		for (int index = 0; index < rows.size(); index++) {
			OperationProgressText.DownloadRow row = rows.get(index);
			DownloadRowNodes nodes = downloadRows.get(row.fileId());
			if (nodes == null) {
				nodes = new DownloadRowNodes();
				downloadRows.put(row.fileId(), nodes);
				downloadList.getChildren().add(Math.min(index, downloadList.getChildren().size()), nodes.box);
			}
			nodes.update(row);
		}
		downloadList.setVisible(!rows.isEmpty());
		downloadList.setManaged(!rows.isEmpty());
	}

	/** One line: the file name on the left, how much of it has arrived on the right. */
	private static final class DownloadRowNodes {

		private final Label name = new Label();
		private final Label size = new Label();
		private final Tooltip tooltip = new Tooltip();
		private final HBox box = new HBox(8, name, size);

		DownloadRowNodes() {
			name.getStyleClass().add("download-row");
			// One line per file; a long name is cut in the middle so the extension stays visible.
			name.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
			name.setMinWidth(0);
			name.setMaxWidth(Double.MAX_VALUE);
			name.setAlignment(Pos.CENTER_LEFT);
			HBox.setHgrow(name, Priority.ALWAYS);
			tooltip.setShowDelay(javafx.util.Duration.millis(300));
			tooltip.setWrapText(true);
			tooltip.setMaxWidth(560);
			name.setTooltip(tooltip);
			size.getStyleClass().add("download-size");
			// A fixed column so the figures line up from one row to the next.
			size.setMinWidth(150);
			size.setAlignment(Pos.CENTER_RIGHT);
		}

		void update(OperationProgressText.DownloadRow row) {
			name.setText(row.text());
			tooltip.setText(row.tooltip());
			size.setText(row.sizeText());
			if (row.finished()) {
				if (!box.getStyleClass().contains("download-done")) {
					box.getStyleClass().add("download-done");
				}
			} else {
				box.getStyleClass().remove("download-done");
			}
		}
	}
}
