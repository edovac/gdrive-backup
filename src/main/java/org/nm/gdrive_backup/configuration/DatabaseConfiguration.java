package org.nm.gdrive_backup.configuration;

import org.nm.gdrive_backup.adapter.out.persistence.SqliteDatabase;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DatabaseConfiguration {

	@Bean
	ApplicationRunner initializeDatabase(SqliteDatabase database) {
		return args -> database.initialize();
	}
}