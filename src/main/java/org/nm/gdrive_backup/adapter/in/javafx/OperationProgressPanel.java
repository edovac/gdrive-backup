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
import javafx.scene.control.ScrollPane;
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
	private final boolean listsDownloads;
	private final DownloadSection downloading = new DownloadSection(OperationProgressText.NOTHING_DOWNLOADING, false);
	private final DownloadSection downloaded = new DownloadSection(OperationProgressText.NOTHING_DOWNLOADED, true);
	private final VBox root;
	private Timeline timeline;
	private Consumer<BackupProgress> progressListener = progress -> {
	};

	/** A panel for an operation that downloads nothing, such as a merge, so it shows no download lists. */
	public OperationProgressPanel(BackupProgressPort progressPort, BackupCancellationUseCase cancellationUseCase,
			String startingText) {
		this(progressPort, cancellationUseCase, startingText, false);
	}

	/**
	 * @param startingText what the panel says before the first progress snapshot arrives
	 * @param listsDownloads whether to list the files being downloaded and the ones already downloaded under the bar
	 */
	public OperationProgressPanel(BackupProgressPort progressPort, BackupCancellationUseCase cancellationUseCase,
			String startingText, boolean listsDownloads) {
		this.progressPort = progressPort;
		this.cancellationUseCase = cancellationUseCase;
		this.startingText = startingText;
		this.listsDownloads = listsDownloads;
		progressBar.setMaxWidth(Double.MAX_VALUE);
		operationLabel.getStyleClass().add("status");
		operationLabel.setWrapText(true);
		driveJobLabel.getStyleClass().add("scope");
		timeLabel.getStyleClass().add("scope");
		cancelButton.getStyleClass().add("secondary-button");
		cancelButton.setOnAction(event -> handleCancelClick());
		root = new VBox(6, progressBar, operationLabel, driveJobLabel, timeLabel, cancelButton);
		if (listsDownloads) {
			// Below the Cancel button, so rows coming and going never move the controls above them.
			VBox lists = new VBox(10, downloading.node(), downloaded.node());
			lists.setFillWidth(true);
			root.getChildren().add(lists);
		}
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
		if (listsDownloads) {
			downloading.update(OperationProgressText.downloadingHeading(0), List.of());
			downloaded.update(OperationProgressText.downloadedHeading(0), List.of());
		}
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
			if (listsDownloads) {
				List<OperationProgressText.DownloadRow> inFlight = OperationProgressText.downloadingRows(progress);
				downloading.update(OperationProgressText.downloadingHeading(inFlight.size()), inFlight);
				downloaded.update(OperationProgressText.downloadedHeading(progress.finishedDownloads()),
						OperationProgressText.downloadedRows(progress));
			}
			progressListener.accept(progress);
		});
	}

	/**
	 * A heading, a placeholder for when there is nothing to list, and the rows. The rows are updated in place, by
	 * file id: the size changes on almost every refresh, so rebuilding them would remove the name label under the
	 * mouse and close its tooltip each time; reused rows keep theirs.
	 */
	private static final class DownloadSection {

		private static final double LIST_HEIGHT = 150;

		private final Label heading = new Label();
		private final Label placeholder;
		private final VBox rows = new VBox(2);
		private final Node body;
		private final VBox node;
		private final Map<String, DownloadRowNodes> rowNodes = new HashMap<>();
		private List<OperationProgressText.DownloadRow> shown = List.of();

		/** @param scrolling whether the rows sit in a fixed-height scrollable box, for a list that keeps growing */
		DownloadSection(String placeholderText, boolean scrolling) {
			heading.getStyleClass().add("download-heading");
			placeholder = new Label(placeholderText);
			placeholder.getStyleClass().add("scope");
			rows.setFillWidth(true);
			if (scrolling) {
				ScrollPane scroll = new ScrollPane(rows);
				scroll.setFitToWidth(true);
				scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
				scroll.setMinHeight(LIST_HEIGHT);
				scroll.setPrefHeight(LIST_HEIGHT);
				scroll.setMaxHeight(LIST_HEIGHT);
				scroll.getStyleClass().add("download-scroll");
				body = scroll;
			} else {
				body = rows;
			}
			node = new VBox(4, heading, placeholder, body);
			node.setFillWidth(true);
			// Nothing to list yet: the placeholder shows and the (empty) rows do not take up space.
			body.setVisible(false);
			body.setManaged(false);
		}

		Node node() {
			return node;
		}

		void update(String headingText, List<OperationProgressText.DownloadRow> newRows) {
			heading.setText(headingText);
			if (newRows.equals(shown)) {
				return;
			}
			shown = newRows;
			Set<String> current = newRows.stream().map(OperationProgressText.DownloadRow::fileId)
					.collect(Collectors.toSet());
			rowNodes.entrySet().removeIf(entry -> {
				if (current.contains(entry.getKey())) {
					return false;
				}
				rows.getChildren().remove(entry.getValue().box);
				return true;
			});
			for (int index = 0; index < newRows.size(); index++) {
				OperationProgressText.DownloadRow row = newRows.get(index);
				DownloadRowNodes nodes = rowNodes.get(row.fileId());
				if (nodes == null) {
					nodes = new DownloadRowNodes();
					rowNodes.put(row.fileId(), nodes);
					rows.getChildren().add(Math.min(index, rows.getChildren().size()), nodes.box);
				}
				nodes.update(row);
			}
			boolean empty = newRows.isEmpty();
			placeholder.setVisible(empty);
			placeholder.setManaged(empty);
			body.setVisible(!empty);
			body.setManaged(!empty);
		}
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
