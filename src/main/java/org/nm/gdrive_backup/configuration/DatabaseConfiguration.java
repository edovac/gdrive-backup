package org.nm.gdrive_backup.configuration;

import java.nio.file.Path;

import org.nm.gdrive_backup.adapter.out.persistence.LocalArchiveReaderAdapter;
import org.nm.gdrive_backup.adapter.out.persistence.LocalArchiveSessionAdapter;
import org.nm.gdrive_backup.adapter.out.persistence.LocalBackupLocationAdapter;
import org.nm.gdrive_backup.adapter.out.persistence.LocalBackupRoot;
import org.nm.gdrive_backup.adapter.out.persistence.SqliteDatabase;
import org.nm.gdrive_backup.domain.port.in.BackupLocationUseCase;
import org.nm.gdrive_backup.domain.port.out.ArchiveReaderPort;
import org.nm.gdrive_backup.domain.port.out.ArchiveSessionPort;
import org.nm.gdrive_backup.domain.port.out.BackupLocationPort;
import org.nm.gdrive_backup.domain.service.BackupActivity;
import org.nm.gdrive_backup.domain.service.BackupLocationService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DatabaseConfiguration {

	@Bean
	LocalBackupRoot backupRoot() {
		return new LocalBackupRoot(defaultDirectory());
	}

	@Bean
	SqliteDatabase sqliteDatabase(LocalBackupRoot backupRoot) {
		return new SqliteDatabase(backupRoot.root().resolve("backup.db"));
	}

	@Bean
	BackupLocationPort backupLocationPort(SqliteDatabase database, LocalBackupRoot backupRoot) {
		return new LocalBackupLocationAdapter(database, backupRoot);
	}

	@Bean
	ArchiveSessionPort archiveSessionPort(LocalBackupRoot backupRoot) {
		return new LocalArchiveSessionAdapter(backupRoot);
	}

	@Bean
	ArchiveReaderPort archiveReaderPort(LocalBackupRoot backupRoot) {
		return new LocalArchiveReaderAdapter(backupRoot);
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
