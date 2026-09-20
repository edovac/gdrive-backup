package org.nm.gdrive_backup.adapter.in.javafx;

import java.time.Instant;

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
import javafx.scene.control.ProgressBar;
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
	private final VBox root;
	private Timeline timeline;

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
		root = new VBox(6, progressBar, operationLabel, driveJobLabel, timeLabel, cancelButton);
		root.setAlignment(Pos.CENTER);
		hide();
	}

	public Node node() {
		return root;
	}

	public void start() {
		root.setVisible(true);
		root.setManaged(true);
		progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
		operationLabel.setText(startingText);
		driveJobLabel.setText("");
		timeLabel.setText("");
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
		});
	}
}
