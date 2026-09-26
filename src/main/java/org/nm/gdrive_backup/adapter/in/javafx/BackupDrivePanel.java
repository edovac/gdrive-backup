package org.nm.gdrive_backup.adapter.in.javafx;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.nm.gdrive_backup.domain.model.AvailableDrive;
import org.nm.gdrive_backup.domain.model.BackupProgress;
import org.nm.gdrive_backup.domain.model.BackupResult;
import org.nm.gdrive_backup.domain.model.ScopeArchives;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The "What" step on the Backup tab: which drive(s) to back up, each row showing its last archive (or chain
 * problem) when idle, and live/final status once a job has started. Selection state lives here rather than in
 * {@code JavaFxApplication}, keyed by drive identity, so it survives cell reuse as the list scrolls.
 */
public final class BackupDrivePanel {

	private final ListView<AvailableDrive> list = new ListView<>();
	private final Label countLabel = new Label();
	private final Hyperlink selectAll = new Hyperlink("Select all");
	private final Hyperlink selectNone = new Hyperlink("None");
	private final VBox root;

	private final Set<AvailableDrive> checkedDrives = new HashSet<>();
	private final Map<String, ScopeArchives> catalogByKey = new HashMap<>();

	private String userEmail;
	private List<AvailableDrive> runningDrives;
	private List<BackupResult> finishedResults;
	private BackupProgress latestProgress;
	private boolean rowsDisabled;

	private Runnable selectionListener = () -> {
	};
	private Consumer<AvailableDrive> focusListener = drive -> {
	};

	public BackupDrivePanel() {
		countLabel.getStyleClass().add("scope");
		selectAll.getStyleClass().add("link-button");
		selectNone.getStyleClass().add("link-button");
		selectAll.setOnAction(event -> {
			checkedDrives.addAll(list.getItems());
			selectionChanged();
		});
		selectNone.setOnAction(event -> {
			checkedDrives.clear();
			selectionChanged();
		});
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox header = new HBox(10, countLabel, spacer, selectAll, selectNone);
		header.setAlignment(Pos.CENTER_LEFT);

		list.setPlaceholder(new Label("No drives loaded"));
		list.setCellFactory(view -> new DriveCell());
		list.setPrefHeight(260);
		list.getSelectionModel().selectedItemProperty().addListener((observable, was, now) -> {
			if (now != null) {
				focusListener.accept(now);
			}
		});

		root = new VBox(8, header, list);
	}

	public Node node() {
		return root;
	}

	public void setDrives(List<AvailableDrive> drives, String userEmail) {
		this.userEmail = userEmail;
		checkedDrives.clear();
		runningDrives = null;
		finishedResults = null;
		latestProgress = null;
		list.getItems().setAll(drives);
		selectionChanged();
	}

	public void clear() {
		userEmail = null;
		checkedDrives.clear();
		catalogByKey.clear();
		runningDrives = null;
		finishedResults = null;
		latestProgress = null;
		list.getItems().clear();
		selectionChanged();
	}

	/** The checked drives, in the order they appear in the list. */
	public List<AvailableDrive> selectedDrives() {
		return list.getItems().stream().filter(checkedDrives::contains).toList();
	}

	/** Refreshes each row's idle chain state from the archive catalog. */
	public void setCatalog(List<ScopeArchives> scopes) {
		catalogByKey.clear();
		for (ScopeArchives scope : scopes) {
			catalogByKey.put(scope.scope().key(), scope);
		}
		list.refresh();
	}

	public void runStarted(List<AvailableDrive> drives) {
		runningDrives = List.copyOf(drives);
		finishedResults = null;
		latestProgress = null;
		list.refresh();
	}

	public void onProgress(BackupProgress progress) {
		latestProgress = progress;
		list.refresh();
	}

	public void runFinished(List<BackupResult> results) {
		finishedResults = results;
		list.refresh();
	}

	public void setDisabled(boolean disabled) {
		rowsDisabled = disabled;
		selectAll.setDisable(disabled);
		selectNone.setDisable(disabled);
		list.refresh();
	}

	/** The drive the admin last clicked, for the contents preview; null before any row is picked. */
	public AvailableDrive focusedDrive() {
		return list.getSelectionModel().getSelectedItem();
	}

	public void onSelectionChanged(Runnable listener) {
		this.selectionListener = listener == null ? () -> {
		} : listener;
	}

	public void onFocusChanged(Consumer<AvailableDrive> listener) {
		this.focusListener = listener == null ? drive -> {
		} : listener;
	}

	private void selectionChanged() {
		countLabel.setText(BackupDriveText.selectionCount(checkedDrives.size(), list.getItems().size()));
		list.refresh();
		selectionListener.run();
	}

	private ScopeArchives catalogFor(AvailableDrive drive) {
		return userEmail == null ? null : catalogByKey.get(BackupDriveText.scopeKey(drive, userEmail));
	}

	private BackupDriveText.DriveStatus statusFor(AvailableDrive drive) {
		if (runningDrives != null) {
			int index = runningDrives.indexOf(drive);
			if (index >= 0) {
				if (finishedResults != null) {
					BackupResult result = index < finishedResults.size() ? finishedResults.get(index) : null;
					return BackupDriveText.resultStatus(result);
				}
				return latestProgress == null ? BackupDriveText.queuedStatus()
						: BackupDriveText.liveStatus(index, latestProgress);
			}
		}
		return BackupDriveText.idleStatus(catalogFor(drive), ZoneId.systemDefault());
	}

	private static void applyTone(Label pill, BackupDriveText.Tone tone) {
		pill.getStyleClass().removeAll("pill-ok", "pill-warn", "pill-error", "pill-info", "pill-neutral");
		pill.getStyleClass().add(switch (tone) {
			case OK -> "pill-ok";
			case WARN -> "pill-warn";
			case ERROR -> "pill-error";
			case INFO -> "pill-info";
			case NEUTRAL -> "pill-neutral";
		});
	}

	/** One drive row: checkbox, icon, name/subtitle, a status pill, and a detail line underneath. */
	private final class DriveCell extends ListCell<AvailableDrive> {

		private final CheckBox checkBox = new CheckBox();
		private final Label icon = new Label();
		private final Label nameLabel = new Label();
		private final Label subtitleLabel = new Label();
		private final VBox nameBox = new VBox(1, nameLabel, subtitleLabel);
		private final Region spacer = new Region();
		private final Label pill = new Label();
		private final HBox top = new HBox(10, checkBox, icon, nameBox, spacer, pill);
		private final Label detailLabel = new Label();
		private final VBox layout = new VBox(4, top, detailLabel);
		private AvailableDrive current;

		DriveCell() {
			icon.getStyleClass().add("drive-icon");
			nameLabel.getStyleClass().add("drive-name");
			subtitleLabel.getStyleClass().add("scope");
			detailLabel.getStyleClass().add("scope");
			detailLabel.setWrapText(true);
			pill.getStyleClass().add("pill");
			top.setAlignment(Pos.CENTER_LEFT);
			HBox.setHgrow(spacer, Priority.ALWAYS);
			layout.setPadding(new Insets(6, 4, 6, 4));
			checkBox.setOnAction(event -> {
				if (current == null) {
					return;
				}
				if (checkBox.isSelected()) {
					checkedDrives.add(current);
				} else {
					checkedDrives.remove(current);
				}
				selectionChanged();
			});
		}

		@Override
		protected void updateItem(AvailableDrive drive, boolean empty) {
			super.updateItem(drive, empty);
			current = drive;
			if (empty || drive == null) {
				setGraphic(null);
				return;
			}
			checkBox.setSelected(checkedDrives.contains(drive));
			checkBox.setDisable(rowsDisabled);
			icon.getStyleClass().removeAll("personal", "shared");
			icon.getStyleClass().add(drive.shared() ? "shared" : "personal");
			icon.setText(drive.shared() ? "⧉" : "▲");
			nameLabel.setText(drive.name());
			subtitleLabel.setText(BackupDriveText.subtitle(drive, userEmail));
			BackupDriveText.DriveStatus status = statusFor(drive);
			pill.setText(status.label());
			applyTone(pill, status.tone());
			detailLabel.setText(status.detail());
			detailLabel.setVisible(!status.detail().isEmpty());
			detailLabel.setManaged(!status.detail().isEmpty());
			setGraphic(layout);
		}
	}
}
