package org.nm.gdrive_backup.configuration;

import org.nm.gdrive_backup.adapter.out.google.GoogleOAuthException;
import org.nm.gdrive_backup.adapter.out.google.GoogleDriveAdapter;
import org.nm.gdrive_backup.adapter.out.google.GoogleServiceAccountAdapter;
import org.nm.gdrive_backup.adapter.out.google.GoogleWorkspaceUserDirectoryAdapter;
import org.nm.gdrive_backup.adapter.out.google.GoogleDriveUsageQuotaAdapter;
import org.nm.gdrive_backup.adapter.out.google.GoogleWorkspaceUsageReportAdapter;
import org.nm.gdrive_backup.adapter.out.google.GoogleCloudQuotaLimitAdapter;
import org.nm.gdrive_backup.domain.port.out.DriveReadPort;
import org.nm.gdrive_backup.domain.port.out.DriveMetadataPort;
import org.nm.gdrive_backup.domain.port.out.DriveChangePort;
import org.nm.gdrive_backup.domain.port.out.DriveContentPort;
import org.nm.gdrive_backup.domain.port.out.DriveFileListingPort;
import org.nm.gdrive_backup.domain.port.out.DriveUsageQuotaPort;
import org.nm.gdrive_backup.domain.port.out.WorkspaceUsageReportPort;
import org.nm.gdrive_backup.domain.port.out.CloudQuotaLimitPort;
import org.nm.gdrive_backup.domain.port.out.WorkspaceUserDirectoryPort;
import org.nm.gdrive_backup.domain.port.in.ServiceAccountAuthenticationUseCase;
import org.nm.gdrive_backup.domain.port.in.DriveUsageQuotaUseCase;
import org.nm.gdrive_backup.domain.port.in.WorkspaceUsageReportUseCase;
import org.nm.gdrive_backup.domain.port.in.CloudQuotaLimitUseCase;
import org.nm.gdrive_backup.domain.port.in.WorkspaceUserListingUseCase;
import org.nm.gdrive_backup.domain.port.out.ServiceAccountCredentialPort;
import org.nm.gdrive_backup.domain.service.ServiceAccountAuthenticationService;
import org.nm.gdrive_backup.domain.service.DriveUsageQuotaService;
import org.nm.gdrive_backup.domain.service.WorkspaceUsageReportService;
import org.nm.gdrive_backup.domain.service.CloudQuotaLimitService;
import org.nm.gdrive_backup.domain.service.WorkspaceUserListingService;
import org.nm.gdrive_backup.domain.service.DriveChangeSyncService;
import org.nm.gdrive_backup.domain.service.FileContentBackupService;
import org.nm.gdrive_backup.domain.service.InitialDriveSyncService;
import org.nm.gdrive_backup.domain.service.DriveBackupService;
import org.nm.gdrive_backup.domain.service.BackupActivity;
import org.nm.gdrive_backup.domain.service.BackupProgressTracker;
import org.nm.gdrive_backup.domain.service.BackupCancellation;
import org.nm.gdrive_backup.domain.port.in.DriveChangeSyncUseCase;
import org.nm.gdrive_backup.domain.port.in.InitialDriveSyncUseCase;
import org.nm.gdrive_backup.domain.port.in.DriveBackupUseCase;
import org.nm.gdrive_backup.domain.port.out.BackupProgressPort;
import org.nm.gdrive_backup.domain.port.out.SyncStatePort;
import org.nm.gdrive_backup.domain.port.out.FileEventPort;
import org.nm.gdrive_backup.domain.port.out.FileMetadataPort;
import org.nm.gdrive_backup.domain.port.out.FileCapturePort;
import org.nm.gdrive_backup.domain.port.out.CaptureStoragePort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;

@Configuration
public class ServiceAccountConfiguration {

	@Bean
	BackupProgressTracker backupProgressTracker(BackupProgressPort backupProgressPort) {
		return new BackupProgressTracker(backupProgressPort, Clock.systemUTC());
	}

	@Bean
	BackupCancellation backupCancellation() {
		return new BackupCancellation();
	}

	@Bean
	@ConditionalOnExpression("'${google.service-account.key:}'.trim().length() > 0")
	GoogleServiceAccountAdapter googleServiceAccountAdapter(ServiceAccountProperties properties) {
		String keyPath = properties.key();
		try {
			return new GoogleServiceAccountAdapter(Path.of(keyPath));
		} catch (IOException | RuntimeException exception) {
			throw new GoogleOAuthException("Unable to load Google service-account key", exception);
		}
	}

	@Bean
	@Primary
	ServiceAccountCredentialPort serviceAccountCredentialPort(
			ObjectProvider<GoogleServiceAccountAdapter> adapterProvider) {
		GoogleServiceAccountAdapter adapter = adapterProvider.getIfAvailable();
		if (adapter != null) {
			return adapter;
		}
		return userEmail -> {
			throw new GoogleOAuthException(
					"Google service-account authentication is not configured. "
							+ "Set GOOGLE_SERVICE_ACCOUNT_KEY to a service-account JSON path.");
		};
	}

	@Bean
	@ConditionalOnExpression("'${google.service-account.key:}'.trim().length() > 0")
	DriveReadPort driveReadPort(@Qualifier("googleServiceAccountAdapter") GoogleServiceAccountAdapter adapter) {
		return new GoogleDriveAdapter(adapter);
	}

	@Bean
	@Primary
	@ConditionalOnExpression("'${google.service-account.key:}'.trim().length() > 0")
	DriveChangePort driveChangePort(@Qualifier("googleServiceAccountAdapter") GoogleServiceAccountAdapter adapter) {
		return new GoogleDriveAdapter(adapter);
	}

	@Bean
	@ConditionalOnExpression("'${google.service-account.key:}'.trim().length() > 0")
	DriveContentPort driveContentPort(@Qualifier("googleServiceAccountAdapter") GoogleServiceAccountAdapter adapter) {
		return new GoogleDriveAdapter(adapter);
	}

	@Bean
	@Primary
	@ConditionalOnExpression("'${google.service-account.key:}'.trim().length() > 0")
	DriveFileListingPort driveFileListingPort(
			@Qualifier("googleServiceAccountAdapter") GoogleServiceAccountAdapter adapter) {
		return new GoogleDriveAdapter(adapter);
	}

	@Bean
	@ConditionalOnExpression("'${google.service-account.key:}'.trim().length() > 0")
	FileContentBackupService fileContentBackupService(
			@Qualifier("driveContentPort") DriveContentPort contentPort,
			CaptureStoragePort storagePort, FileCapturePort fileCapturePort) {
		return new FileContentBackupService(contentPort, storagePort, fileCapturePort);
	}

	@Bean
	DriveChangeSyncUseCase driveChangeSyncUseCase(
			@Qualifier("driveChangePort") ObjectProvider<DriveChangePort> changePortProvider, SyncStatePort syncStatePort,
			FileMetadataPort fileMetadataPort, FileEventPort fileEventPort,
			ObjectProvider<FileContentBackupService> contentBackupProvider, BackupProgressTracker progressTracker,
			BackupCancellation cancellation) {
		DriveChangePort changePort = changePortProvider.getIfAvailable();
		if (changePort == null) {
			return (access, scope) -> {
				throw new GoogleOAuthException(
						"Drive change synchronization is not configured. "
								+ "Set GOOGLE_SERVICE_ACCOUNT_KEY to a service-account JSON path.");
			};
		}
		return new DriveChangeSyncService(changePort, syncStatePort, fileMetadataPort, fileEventPort,
				contentBackupProvider.getIfAvailable(), progressTracker, cancellation);
	}

	@Bean
	InitialDriveSyncUseCase initialDriveSyncUseCase(
			@Qualifier("driveFileListingPort") ObjectProvider<DriveFileListingPort> fileListingPortProvider,
			@Qualifier("driveChangePort") ObjectProvider<DriveChangePort> changePortProvider,
			FileMetadataPort fileMetadataPort,
			SyncStatePort syncStatePort, ObjectProvider<FileContentBackupService> contentBackupProvider,
			BackupProgressTracker progressTracker, BackupCancellation cancellation) {
		DriveFileListingPort fileListingPort = fileListingPortProvider.getIfAvailable();
		DriveChangePort changePort = changePortProvider.getIfAvailable();
		if (fileListingPort == null || changePort == null) {
			return (access, scope) -> {
				throw new GoogleOAuthException(
						"Initial Drive synchronization is not configured. "
								+ "Set GOOGLE_SERVICE_ACCOUNT_KEY to a service-account JSON path.");
			};
		}
		return new InitialDriveSyncService(fileListingPort, changePort, fileMetadataPort, syncStatePort,
				contentBackupProvider.getIfAvailable(), progressTracker, cancellation);
	}

	@Bean
	DriveBackupUseCase driveBackupUseCase(SyncStatePort syncStatePort,
			InitialDriveSyncUseCase initialDriveSyncUseCase, DriveChangeSyncUseCase driveChangeSyncUseCase,
			BackupActivity backupActivity, DriveMetadataPort driveMetadataPort, BackupProgressTracker progressTracker,
			BackupCancellation cancellation) {
		return new DriveBackupService(syncStatePort, initialDriveSyncUseCase, driveChangeSyncUseCase,
				backupActivity, driveMetadataPort, progressTracker, cancellation);
	}

	@Bean
	@ConditionalOnExpression("'${google.service-account.key:}'.trim().length() > 0")
	WorkspaceUserDirectoryPort workspaceUserDirectoryPort(
			@Qualifier("googleServiceAccountAdapter") GoogleServiceAccountAdapter adapter) {
		return new GoogleWorkspaceUserDirectoryAdapter(adapter);
	}

	@Bean
	@ConditionalOnExpression("'${google.service-account.key:}'.trim().length() > 0")
	DriveUsageQuotaPort driveUsageQuotaPort(
			@Qualifier("googleServiceAccountAdapter") GoogleServiceAccountAdapter adapter) {
		return new GoogleDriveUsageQuotaAdapter(adapter);
	}

	@Bean
	@ConditionalOnExpression("'${google.service-account.key:}'.trim().length() > 0")
	WorkspaceUsageReportPort workspaceUsageReportPort(
			@Qualifier("googleServiceAccountAdapter") GoogleServiceAccountAdapter adapter) {
		return new GoogleWorkspaceUsageReportAdapter(adapter);
	}

	@Bean
	@ConditionalOnExpression("'${google.service-account.key:}'.trim().length() > 0")
	CloudQuotaLimitPort cloudQuotaLimitPort(
			@Qualifier("googleServiceAccountAdapter") GoogleServiceAccountAdapter adapter,
			ServiceAccountProperties properties) {
		return new GoogleCloudQuotaLimitAdapter(adapter, properties.projectId());
	}

	@Bean
	@ConditionalOnExpression("'${google.service-account.key:}'.trim().length() == 0")
	WorkspaceUserDirectoryPort workspaceUserDirectoryPortFallback() {
		return access -> {
			throw new GoogleOAuthException(
					"Google Workspace user listing is not configured. "
						+ "Set GOOGLE_SERVICE_ACCOUNT_KEY to a service-account JSON path.");
		};
	}

	@Bean
	ServiceAccountAuthenticationUseCase serviceAccountAuthenticationUseCase(
			ServiceAccountCredentialPort credentialPort) {
		return new ServiceAccountAuthenticationService(credentialPort);
	}

	@Bean
	WorkspaceUserListingUseCase workspaceUserListingUseCase(
			WorkspaceUserDirectoryPort workspaceUserDirectoryPort) {
		return new WorkspaceUserListingService(workspaceUserDirectoryPort);
	}

	@Bean
	DriveUsageQuotaUseCase driveUsageQuotaUseCase(ObjectProvider<DriveUsageQuotaPort> portProvider) {
		DriveUsageQuotaPort port = portProvider.getIfAvailable();
		if (port == null) {
			return access -> {
				throw new GoogleOAuthException(
						"Google Drive usage quota is not configured. "
								+ "Set GOOGLE_SERVICE_ACCOUNT_KEY to a service-account JSON path.");
			};
		}
		return new DriveUsageQuotaService(port);
	}

	@Bean
	WorkspaceUsageReportUseCase workspaceUsageReportUseCase(ObjectProvider<WorkspaceUsageReportPort> portProvider) {
		WorkspaceUsageReportPort port = portProvider.getIfAvailable();
		if (port == null) {
			return access -> {
				throw new GoogleOAuthException(
						"Workspace usage reports are not configured. "
								+ "Set GOOGLE_SERVICE_ACCOUNT_KEY and authorize the Reports scope.");
			};
		}
		return new WorkspaceUsageReportService(port);
	}

	@Bean
	CloudQuotaLimitUseCase cloudQuotaLimitUseCase(ObjectProvider<CloudQuotaLimitPort> portProvider) {
		CloudQuotaLimitPort port = portProvider.getIfAvailable();
		if (port == null) {
			return () -> {
				throw new GoogleOAuthException(
						"Cloud quota limits are not configured. Set GOOGLE_CLOUD_PROJECT_ID.");
			};
		}
		return new CloudQuotaLimitService(port);
	}
}
