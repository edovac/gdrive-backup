package org.nm.gdrive_backup.configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.nm.gdrive_backup.adapter.out.persistence.LocalVersionStorageAdapter;
import org.nm.gdrive_backup.adapter.out.persistence.SqliteDatabase;
import org.nm.gdrive_backup.domain.port.out.VersionStoragePort;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DatabaseConfiguration {

	@Bean
	SqliteDatabase sqliteDatabase(BackupDatabaseProperties properties) {
		Path databasePath = properties.database() == null || properties.database().isBlank()
				? Path.of(System.getProperty("user.home"), ".gdrive-backup", "backup.db")
				: Path.of(properties.database());
		try {
			Files.createDirectories(databasePath.toAbsolutePath().getParent());
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to create SQLite database directory", exception);
		}
		return new SqliteDatabase(databasePath);
	}

	@Bean
	VersionStoragePort versionStorage(BackupStorageProperties properties) {
		Path backupRoot = properties.root() == null || properties.root().isBlank()
				? Path.of(System.getProperty("user.home"), ".gdrive-backup", "backupRoot")
				: Path.of(properties.root());
		return new LocalVersionStorageAdapter(backupRoot);
	}

	@Bean
	ApplicationRunner initializeDatabase(SqliteDatabase database) {
		return args -> database.initialize();
	}
}