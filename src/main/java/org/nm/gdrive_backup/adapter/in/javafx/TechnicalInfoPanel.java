package org.nm.gdrive_backup.adapter.in.javafx;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.port.in.CloudQuotaLimitUseCase;
import org.nm.gdrive_backup.domain.port.in.DriveUsageQuotaUseCase;
import org.nm.gdrive_backup.domain.port.in.ServiceAccountAuthenticationUseCase;
import org.nm.gdrive_backup.domain.port.in.WorkspaceUsageReportUseCase;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

/**
 * The Technical info view: a dashboard of three cards (Drive storage usage, Workspace usage report, Cloud API quota
 * limits). Nothing is fetched until the admin presses a card's Refresh button, so no Google API quota is spent just by
 * opening the view. Each card loads and fails independently.
 */
public final class TechnicalInfoPanel {

	private static final double CARD_WIDTH = 360;

	private final ServiceAccountAuthenticationUseCase authenticationUseCase;
	private final DriveUsageQuotaUseCase driveUsageQuotaUseCase;
	private final WorkspaceUsageReportUseCase workspaceUsageReportUseCase;
	private final CloudQuotaLimitUseCase cloudQuotaLimitUseCase;
	private final Supplier<String> selectedUserEmail;

	private final Card storageCard = new Card("Drive storage usage");
	private final Card reportCard = new Card("Workspace usage report");
	private final Card cloudCard = new Card("Cloud API quota limits");
	private final VBox root;

	/**
	 * @param selectedUserEmail the Workspace user whose storage and report are shown; read on the FX thread each time
	 *        a card is refreshed
	 */
	public TechnicalInfoPanel(ServiceAccountAuthenticationUseCase authenticationUseCase,
			DriveUsageQuotaUseCase driveUsageQuotaUseCase, WorkspaceUsageReportUseCase workspaceUsageReportUseCase,
			CloudQuotaLimitUseCase cloudQuotaLimitUseCase, Supplier<String> selectedUserEmail) {
		this.authenticationUseCase = authenticationUseCase;
		this.driveUsageQuotaUseCase = driveUsageQuotaUseCase;
		this.workspaceUsageReportUseCase = workspaceUsageReportUseCase;
		this.cloudQuotaLimitUseCase = cloudQuotaLimitUseCase;
		this.selectedUserEmail = selectedUserEmail;

		storageCard.refresh.setOnAction(event -> refreshStorage());
		reportCard.refresh.setOnAction(event -> refreshReport());
		cloudCard.refresh.setOnAction(event -> refreshCloud());

		Button refreshAll = new Button("Refresh all");
		refreshAll.getStyleClass().add("primary-button");
		refreshAll.setOnAction(event -> {
			refreshStorage();
			refreshReport();
			refreshCloud();
		});
		Label title = new Label("Technical info");
		title.getStyleClass().add("subtitle");
		Label note = new Label("Nothing is loaded automatically. Storage and the report follow the selected user.");
		note.getStyleClass().add("scope");
		note.setWrapText(true);

		FlowPane cards = new FlowPane(12, 12, storageCard.node, reportCard.node, cloudCard.node);
		cards.setAlignment(Pos.TOP_LEFT);
		root = new VBox(12, title, note, refreshAll, cards);
		root.setPadding(new Insets(12));
		reset();
	}

	public Node node() {
		return root;
	}

	/** Clears every card back to its "press Refresh" state, for example after signing out. */
	public void hide() {
		reset();
	}

	/** The selected user changed: their storage and report are stale. The project-wide API quota is not. */
	public void onUserChanged() {
		storageCard.reset();
		reportCard.reset();
	}

	private void reset() {
		storageCard.reset();
		reportCard.reset();
		cloudCard.reset();
	}

	private void refreshStorage() {
		String userEmail = selectedUserEmail.get();
		if (authenticationUseCase == null || driveUsageQuotaUseCase == null || userEmail == null
				|| userEmail.isBlank()) {
			storageCard.unavailable(TechnicalInfoText.storageUnavailable());
			return;
		}
		load(storageCard, TechnicalInfoText.storageLoading(userEmail), () -> {
			ServiceAccountAccess access = authenticationUseCase.authenticateAs(userEmail);
			return driveUsageQuotaUseCase.getUsageQuota(access);
		}, TechnicalInfoText::storageLines, TechnicalInfoText::storageStatus, TechnicalInfoText::storageFailed);
	}

	private void refreshReport() {
		String userEmail = selectedUserEmail.get();
		if (authenticationUseCase == null || workspaceUsageReportUseCase == null || userEmail == null
				|| userEmail.isBlank()) {
			reportCard.unavailable(TechnicalInfoText.reportUnavailable());
			return;
		}
		load(reportCard, TechnicalInfoText.reportLoading(), () -> {
			ServiceAccountAccess access = authenticationUseCase.authenticateAs(userEmail);
			return workspaceUsageReportUseCase.getLatestReport(access);
		}, TechnicalInfoText::reportLines, TechnicalInfoText::reportStatus, TechnicalInfoText::reportFailed);
	}

	private void refreshCloud() {
		if (cloudQuotaLimitUseCase == null) {
			cloudCard.unavailable(TechnicalInfoText.cloudUnavailable());
			return;
		}
		load(cloudCard, TechnicalInfoText.cloudLoading(), cloudQuotaLimitUseCase::listQuotaLimits,
				limits -> limits.stream().map(TechnicalInfoText::cloudLine).toList(),
				limits -> TechnicalInfoText.cloudStatus(), TechnicalInfoText::cloudFailed);
	}

	/** Runs the fetch off the FX thread and reports the outcome on the card when it returns. */
	private static <T> void load(Card card, String loadingMessage, Supplier<T> fetch,
			Function<T, List<String>> lines, Function<T, String> status, Function<String, String> failure) {
		card.loading(loadingMessage);
		CompletableFuture.supplyAsync(fetch).whenComplete((result, error) -> Platform.runLater(() -> {
			if (error != null) {
				card.failed(failure.apply(TechnicalInfoText.reason(error)));
			} else {
				card.loaded(status.apply(result), lines.apply(result));
			}
		}));
	}

	/** One dashboard card: title, status line, detail list, "updated" time and its own Refresh button. */
	private static final class Card {

		private final Label status = new Label();
		private final Label updated = new Label();
		private final ListView<String> details = new ListView<>();
		private final Button refresh = new Button("Refresh");
		private final VBox node;

		Card(String heading) {
			Label title = new Label(heading);
			title.getStyleClass().add("subtitle");
			status.getStyleClass().add("status");
			status.setWrapText(true);
			updated.getStyleClass().add("scope");
			details.setPlaceholder(new Label(TechnicalInfoText.notLoaded()));
			details.setPrefHeight(220);
			refresh.getStyleClass().add("secondary-button");
			node = new VBox(6, title, status, details, updated, refresh);
			node.setPrefWidth(CARD_WIDTH);
			node.setPadding(new Insets(10));
			node.setStyle("-fx-background-color: white; -fx-background-radius: 8;"
					+ " -fx-border-color: #dadce0; -fx-border-radius: 8;");
		}

		void reset() {
			status.setText("");
			updated.setText("");
			details.getItems().clear();
			refresh.setDisable(false);
		}

		void loading(String message) {
			status.setText(message);
			refresh.setDisable(true);
		}

		void loaded(String message, List<String> lines) {
			status.setText(message);
			details.getItems().setAll(lines);
			updated.setText(TechnicalInfoText.updated(Instant.now(), ZoneId.systemDefault()));
			refresh.setDisable(false);
		}

		void failed(String message) {
			status.setText(message);
			details.getItems().clear();
			refresh.setDisable(false);
		}

		void unavailable(String message) {
			failed(message);
			updated.setText("");
		}
	}
}
