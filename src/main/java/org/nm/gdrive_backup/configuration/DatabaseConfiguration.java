package org.nm.gdrive_backup.configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.nm.gdrive_backup.adapter.out.persistence.LocalBackupLocationAdapter;
import org.nm.gdrive_backup.adapter.out.persistence.LocalVersionStorageAdapter;
import org.nm.gdrive_backup.adapter.out.persistence.SqliteDatabase;
import org.nm.gdrive_backup.domain.port.in.BackupLocationUseCase;
import org.nm.gdrive_backup.domain.port.out.BackupLocationPort;
import org.nm.gdrive_backup.domain.service.BackupActivity;
import org.nm.gdrive_backup.domain.service.BackupLocationService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DatabaseConfiguration {

	@Bean
	SqliteDatabase sqliteDatabase() {
		Path databasePath = defaultDirectory().resolve("backup.db");
		try {
			Files.createDirectories(databasePath.getParent());
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to create SQLite database directory", exception);
		}
		return new SqliteDatabase(databasePath);
	}

	@Bean
	LocalVersionStorageAdapter versionStorage() {
		return new LocalVersionStorageAdapter(defaultDirectory().resolve("backupRoot"));
	}

	@Bean
	BackupLocationPort backupLocationPort(SqliteDatabase database, LocalVersionStorageAdapter versionStorage) {
		return new LocalBackupLocationAdapter(database, versionStorage);
	}

	@Bean
	BackupActivity backupActivity() {
		return new BackupActivity();
	}

	@Bean
	BackupLocationUseCase backupLocationUseCase(BackupLocationPort backupLocationPort, BackupActivity backupActivity) {
		return new BackupLocationService(backupLocationPort, backupActivity);
	}

	@Bean
	ApplicationRunner initializeDatabase(SqliteDatabase database) {
		return args -> database.initialize();
	}

	/** Every launch starts here; the admin changes the locations for the session in the UI. */
	private static Path defaultDirectory() {
		return Path.of(System.getProperty("user.home"), ".gdrive-backup");
	}
}
