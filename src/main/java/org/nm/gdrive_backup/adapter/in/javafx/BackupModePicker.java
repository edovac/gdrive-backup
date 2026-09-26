package org.nm.gdrive_backup.adapter.in.javafx;

import org.nm.gdrive_backup.domain.model.BackupMode;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Two mode cards (Incremental / Full) the admin picks between on the Backup tab's "How" step. */
public final class BackupModePicker {

	private final ToggleGroup group = new ToggleGroup();
	private final RadioButton incrementalButton = new RadioButton();
	private final RadioButton fullButton = new RadioButton();
	private final VBox incrementalCard;
	private final VBox fullCard;
	private final HBox root;
	private Runnable onChange = () -> {
	};

	public BackupModePicker() {
		incrementalCard = optionCard(incrementalButton, BackupMode.INCREMENTAL);
		fullCard = optionCard(fullButton, BackupMode.FULL);
		incrementalButton.setToggleGroup(group);
		fullButton.setToggleGroup(group);
		incrementalButton.setSelected(true);
		group.selectedToggleProperty().addListener((observable, was, is) -> {
			updateSelectedStyles();
			onChange.run();
		});
		updateSelectedStyles();

		root = new HBox(12, incrementalCard, fullCard);
		HBox.setHgrow(incrementalCard, Priority.ALWAYS);
		HBox.setHgrow(fullCard, Priority.ALWAYS);
	}

	public Node node() {
		return root;
	}

	public BackupMode value() {
		return fullButton.isSelected() ? BackupMode.FULL : BackupMode.INCREMENTAL;
	}

	public void reset() {
		incrementalButton.setSelected(true);
	}

	public void setDisabled(boolean disabled) {
		incrementalButton.setDisable(disabled);
		fullButton.setDisable(disabled);
	}

	public void onChange(Runnable listener) {
		this.onChange = listener == null ? () -> {
		} : listener;
	}

	private void updateSelectedStyles() {
		incrementalCard.getStyleClass().remove("selected");
		fullCard.getStyleClass().remove("selected");
		(incrementalButton.isSelected() ? incrementalCard : fullCard).getStyleClass().add("selected");
	}

	private static VBox optionCard(RadioButton button, BackupMode mode) {
		button.setText(BackupDriveText.modeLabel(mode) + " backup");
		Label description = new Label(BackupDriveText.modeDescription(mode));
		description.getStyleClass().add("scope");
		description.setWrapText(true);
		VBox card = new VBox(6, button, description);
		card.getStyleClass().add("mode-option");
		card.setOnMouseClicked(event -> button.setSelected(true));
		return card;
	}
}
