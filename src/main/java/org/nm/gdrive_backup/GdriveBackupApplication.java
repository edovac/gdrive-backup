package org.nm.gdrive_backup;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GdriveBackupApplication {

	public static void main(String[] args) {
		ConfigurableApplicationContext springContext = new SpringApplicationBuilder(GdriveBackupApplication.class)
				.headless(false)
				.run(args);
		JavaFxApplication.setSpringContext(springContext);
		JavaFxApplication.launch(JavaFxApplication.class, args);
	}

}
