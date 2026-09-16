package org.nm.gdrive_backup.configuration;

import java.nio.file.Path;

import org.nm.gdrive_backup.adapter.out.persistence.LocalArchiveWriterAdapter;
import org.nm.gdrive_backup.adapter.out.persistence.LocalBackupLocationAdapter;
import org.nm.gdrive_backup.adapter.out.persistence.LocalCaptureStorageAdapter;
import org.nm.gdrive_backup.adapter.out.persistence.SqliteDatabase;
import org.nm.gdrive_backup.domain.port.in.BackupLocationUseCase;
import org.nm.gdrive_backup.domain.port.out.ArchiveWriterPort;
import org.nm.gdrive_backup.domain.port.out.BackupLocationPort;
import org.nm.gdrive_backup.domain.service.BackupActivity;
import org.nm.gdrive_backup.domain.service.BackupLocationService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DatabaseConfiguration {

	@Bean
	LocalCaptureStorageAdapter captureStorage() {
		return new LocalCaptureStorageAdapter(defaultDirectory());
	}

	@Bean
	SqliteDatabase sqliteDatabase(LocalCaptureStorageAdapter captureStorage) {
		return new SqliteDatabase(captureStorage.root().resolve("backup.db"));
	}

	@Bean
	BackupLocationPort backupLocationPort(SqliteDatabase database, LocalCaptureStorageAdapter captureStorage) {
		return new LocalBackupLocationAdapter(database, captureStorage);
	}

	@Bean
	ArchiveWriterPort archiveWriterPort(LocalCaptureStorageAdapter captureStorage) {
		return new LocalArchiveWriterAdapter(captureStorage);
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

	/** Every launch starts here; the admin changes the location for the session in the UI. */
	private static Path defaultDirectory() {
		return Path.of(System.getProperty("user.home"), ".gdrive-backup");
	}
}
