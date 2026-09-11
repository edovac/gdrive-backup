package org.nm.gdrive_backup;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.springframework.context.ConfigurableApplicationContext;

public class JavaFxApplication extends Application {

	private static ConfigurableApplicationContext springContext;

	static void setSpringContext(ConfigurableApplicationContext context) {
		springContext = context;
	}

	@Override
	public void start(Stage stage) {
		Button minimize = new Button("-");
		Button maximize = new Button("+");
		Button close = new Button("x");

		minimize.setOnAction(event -> stage.setIconified(true));
		maximize.setOnAction(event -> stage.setMaximized(!stage.isMaximized()));
		close.setOnAction(event -> Platform.exit());

		HBox controls = new HBox(minimize, maximize, close);
		controls.setAlignment(Pos.TOP_RIGHT);
		Scene scene = new Scene(controls, 640, 400);
		stage.initStyle(StageStyle.UNDECORATED);
		stage.setScene(scene);
		stage.setOnCloseRequest(event -> Platform.exit());
		stage.show();
	}

	@Override
	public void stop() {
		if (springContext != null) {
			springContext.close();
		}
	}

}