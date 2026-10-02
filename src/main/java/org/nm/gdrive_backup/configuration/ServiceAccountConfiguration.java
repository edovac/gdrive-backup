package org.nm.gdrive_backup.configuration;

import org.nm.gdrive_backup.adapter.out.google.GoogleDriveAdapter;
import org.nm.gdrive_backup.adapter.out.google.GoogleServiceAccountAdapter;
import org.nm.gdrive_backup.adapter.out.google.GoogleWorkspaceUserDirectoryAdapter;
import org.nm.gdrive_backup.adapter.out.google.GoogleDriveUsageQuotaAdapter;
import org.nm.gdrive_backup.adapter.out.google.GoogleDriveUserProfileAdapter;
import org.nm.gdrive_backup.adapter.out.google.GoogleWorkspaceUsageReportAdapter;
import org.nm.gdrive_backup.adapter.out.google.GoogleCloudQuotaLimitAdapter;
import org.nm.gdrive_backup.domain.port.out.DriveReadPort;
import org.nm.gdrive_backup.domain.port.out.DriveMetadataPort;
import org.nm.gdrive_backup.domain.port.out.DriveChangePort;
import org.nm.gdrive_backup.domain.port.out.DriveContentPort;
import org.nm.gdrive_backup.domain.port.out.DriveFileListingPort;
import org.nm.gdrive_backup.domain.port.out.DriveUsageQuotaPort;
import org.nm.gdrive_backup.domain.port.out.DriveUserProfilePort;
import org.nm.gdrive_backup.domain.port.out.WorkspaceUsageReportPort;
import org.nm.gdrive_backup.domain.port.out.CloudQuotaLimitPort;
import org.nm.gdrive_backup.domain.port.out.WorkspaceUserDirectoryPort;
import org.nm.gdrive_backup.domain.port.out.CredentialStoragePort;
import org.nm.gdrive_backup.domain.port.in.ServiceAccountAuthenticationUseCase;
import org.nm.gdrive_backup.domain.port.in.DriveUsageQuotaUseCase;
import org.nm.gdrive_backup.domain.port.in.DriveUserProfileUseCase;
import org.nm.gdrive_backup.domain.port.in.WorkspaceUsageReportUseCase;
import org.nm.gdrive_backup.domain.port.in.CloudQuotaLimitUseCase;
import org.nm.gdrive_backup.domain.port.in.WorkspaceUserListingUseCase;
import org.nm.gdrive_backup.domain.port.out.ServiceAccountCredentialPort;
import org.nm.gdrive_backup.domain.service.ServiceAccountAuthenticationService;
import org.nm.gdrive_backup.domain.service.DriveUsageQuotaService;
import org.nm.gdrive_backup.domain.service.DriveUserProfileService;
import org.nm.gdrive_backup.domain.service.WorkspaceUsageReportService;
import org.nm.gdrive_backup.domain.service.CloudQuotaLimitService;
import org.nm.gdrive_backup.domain.service.WorkspaceUserListingService;
import org.nm.gdrive_backup.domain.port.in.DownloadFailureReportUseCase;
import org.nm.gdrive_backup.domain.port.out.DownloadFailurePort;
import org.nm.gdrive_backup.domain.service.DownloadFailureReportService;
import org.nm.gdrive_backup.domain.service.DriveChangeSyncService;
import org.nm.gdrive_backup.domain.service.DownloadConcurrencyService;
import org.nm.gdrive_backup.domain.port.in.DownloadConcurrencyUseCase;
import org.nm.gdrive_backup.domain.service.PersonalDriveContentService;
import org.nm.gdrive_backup.domain.port.in.PersonalDriveContentUseCase;
import org.nm.gdrive_backup.domain.service.FileContentStreamingService;
import org.nm.gdrive_backup.domain.service.ArchiveRunPlanner;
import org.nm.gdrive_backup.domain.service.ArchiveMergeService;
import org.nm.gdrive_backup.domain.service.ArchiveCatalogService;
import org.nm.gdrive_backup.domain.service.FileHistoryService;
import org.nm.gdrive_backup.domain.port.in.FileHistoryUseCase;
import org.nm.gdrive_backup.domain.service.ArchiveDeletionService;
import org.nm.gdrive_backup.domain.port.in.ArchiveDeletionUseCase;
import org.nm.gdrive_backup.domain.port.out.ArchiveDeletionCommitPort;
import org.nm.gdrive_backup.domain.port.in.ArchiveCatalogUseCase;
import org.nm.gdrive_backup.domain.port.out.ArchiveStoragePort;
import org.nm.gdrive_backup.domain.port.in.ArchiveMergeUseCase;
import org.nm.gdrive_backup.domain.port.out.ArchiveReaderPort;
import org.nm.gdrive_backup.domain.service.InitialDriveSyncService;
import org.nm.gdrive_backup.domain.service.DriveBackupService;
import org.nm.gdrive_backup.domain.service.BackupActivity;
import org.nm.gdrive_backup.domain.service.BackupProgressTracker;
import org.nm.gdrive_backup.domain.service.BackupCancellation;
import org.nm.gdrive_backup.domain.port.in.DriveChangeSyncUseCase;
import org.nm.gdrive_backup.domain.port.in.InitialDriveSyncUseCase;
import org.nm.gdrive_backup.domain.port.in.DriveBackupUseCase;
import org.nm.gdrive_backup.domain.port.out.ArchivePort;
import org.nm.gdrive_backup.domain.port.out.ArchiveSessionPort;
import org.nm.gdrive_backup.domain.port.out.SyncCommitPort;
import org.nm.gdrive_backup.domain.port.out.BackupProgressPort;
import org.nm.gdrive_backup.domain.port.out.SyncStatePort;
import org.nm.gdrive_backup.domain.port.out.FileMetadataPort;
import org.nm.gdrive_backup.domain.port.out.FileCapturePort;
import org.nm.gdrive_backup.domain.port.out.FileEventPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;

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
	GoogleServiceAccountAdapter googleServiceAccountAdapter(CredentialStoragePort credentialStoragePort) {
		return new GoogleServiceAccountAdapter(credentialStoragePort);
	}

	@Bean
	@Primary
	ServiceAccountCredentialPort serviceAccountCredentialPort(GoogleServiceAccountAdapter adapter) {
		return adapter;
	}

	@Bean
	DriveReadPort driveReadPort(GoogleServiceAccountAdapter adapter) {
		return new GoogleDriveAdapter(adapter);
	}

	@Bean
	@Primary
	DriveChangePort driveChangePort(GoogleServiceAccountAdapter adapter) {
		return new GoogleDriveAdapter(adapter);
	}

	@Bean
	DriveContentPort driveContentPort(GoogleServiceAccountAdapter adapter) {
		return new GoogleDriveAdapter(adapter);
	}

	@Bean
	@Primary
	DriveFileListingPort driveFileListingPort(GoogleServiceAccountAdapter adapter) {
		return new GoogleDriveAdapter(adapter);
	}

	@Bean
	ArchiveRunPlanner archiveRunPlanner(ArchivePort archivePort) {
		return new ArchiveRunPlanner(archivePort);
	}

	@Bean
	ArchiveCatalogUseCase archiveCatalogUseCase(ArchivePort archivePort, ArchiveStoragePort archiveStoragePort) {
		return new ArchiveCatalogService(archivePort, archiveStoragePort);
	}

	@Bean
	FileHistoryUseCase fileHistoryUseCase(FileMetadataPort fileMetadataPort, FileEventPort fileEventPort,
			FileCapturePort fileCapturePort, ArchivePort archivePort) {
		return new FileHistoryService(fileMetadataPort, fileEventPort, fileCapturePort, archivePort);
	}

	@Bean
	ArchiveDeletionUseCase archiveDeletionUseCase(ArchivePort archivePort, ArchiveReaderPort archiveReaderPort,
			ArchiveStoragePort archiveStoragePort, FileCapturePort fileCapturePort, FileEventPort fileEventPort,
			FileMetadataPort fileMetadataPort, ArchiveDeletionCommitPort deletionCommitPort,
			BackupActivity backupActivity, BackupProgressTracker progressTracker, BackupCancellation cancellation) {
		return new ArchiveDeletionService(archivePort, archiveReaderPort, archiveStoragePort, fileCapturePort,
				fileEventPort, fileMetadataPort, deletionCommitPort, backupActivity, progressTracker, cancellation);
	}

	/** Archive-only, so it needs no Google credentials and is always wired. */
	@Bean
	ArchiveMergeUseCase archiveMergeUseCase(ArchivePort archivePort, ArchiveReaderPort archiveReaderPort,
			ArchiveSessionPort archiveSessionPort, ArchiveRunPlanner archiveRunPlanner, SyncCommitPort syncCommitPort,
			BackupActivity backupActivity, BackupProgressTracker progressTracker, BackupCancellation cancellation) {
		return new ArchiveMergeService(archivePort, archiveReaderPort, archiveSessionPort, archiveRunPlanner,
				syncCommitPort, backupActivity, progressTracker, cancellation);
	}

	@Bean
	FileContentStreamingService fileContentStreamingService(@Qualifier("driveContentPort") DriveContentPort contentPort) {
		return new FileContentStreamingService(contentPort);
	}

	/** Starts at the configured value; the admin changes it for the session in Settings. */
	@Bean
	DownloadConcurrencyUseCase downloadConcurrencyUseCase(BackupProperties backupProperties,
			BackupActivity backupActivity) {
		return new DownloadConcurrencyService(backupProperties.downloadConcurrency(), backupActivity);
	}

	@Bean
	DownloadFailureReportUseCase downloadFailureReportUseCase(DownloadFailurePort downloadFailurePort) {
		return new DownloadFailureReportService(downloadFailurePort);
	}

	/** Starts at the configured value; the admin changes it for the session in Settings. */
	@Bean
	PersonalDriveContentUseCase personalDriveContentUseCase(BackupProperties backupProperties,
			BackupActivity backupActivity) {
		return new PersonalDriveContentService(backupProperties.personalDriveContent(), backupActivity);
	}

	@Bean
	DriveChangeSyncUseCase driveChangeSyncUseCase(
			@Qualifier("driveChangePort") DriveChangePort changePort, SyncStatePort syncStatePort,
			FileMetadataPort fileMetadataPort, FileContentStreamingService contentStreamingService,
			ArchiveSessionPort archiveSessionPort, ArchiveRunPlanner archiveRunPlanner, SyncCommitPort syncCommitPort,
			BackupProgressTracker progressTracker, BackupCancellation cancellation,
			DownloadConcurrencyUseCase downloadConcurrency, PersonalDriveContentUseCase personalDriveContent,
			DownloadFailurePort downloadFailurePort, BackupProperties backupProperties) {
		return new DriveChangeSyncService(changePort, syncStatePort, fileMetadataPort, contentStreamingService,
				archiveSessionPort, archiveRunPlanner, syncCommitPort, progressTracker, cancellation,
				downloadConcurrency::current, personalDriveContent::current,
				downloadFailurePort, backupProperties::maxDownloadFailures);
	}

	@Bean
	InitialDriveSyncUseCase initialDriveSyncUseCase(
			@Qualifier("driveFileListingPort") DriveFileListingPort fileListingPort,
			@Qualifier("driveChangePort") DriveChangePort changePort,
			FileContentStreamingService contentStreamingService,
			ArchiveSessionPort archiveSessionPort, ArchiveRunPlanner archiveRunPlanner, SyncCommitPort syncCommitPort,
			BackupProgressTracker progressTracker, BackupCancellation cancellation,
			DownloadConcurrencyUseCase downloadConcurrency, PersonalDriveContentUseCase personalDriveContent,
			DownloadFailurePort downloadFailurePort, BackupProperties backupProperties) {
		return new InitialDriveSyncService(fileListingPort, changePort, contentStreamingService, archiveSessionPort,
				archiveRunPlanner, syncCommitPort, progressTracker, cancellation, downloadConcurrency::current,
				personalDriveContent::current,
				downloadFailurePort, backupProperties::maxDownloadFailures);
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
	WorkspaceUserDirectoryPort workspaceUserDirectoryPort(GoogleServiceAccountAdapter adapter) {
		return new GoogleWorkspaceUserDirectoryAdapter(adapter);
	}

	@Bean
	DriveUsageQuotaPort driveUsageQuotaPort(GoogleServiceAccountAdapter adapter) {
		return new GoogleDriveUsageQuotaAdapter(adapter);
	}

	@Bean
	DriveUserProfilePort driveUserProfilePort(GoogleServiceAccountAdapter adapter) {
		return new GoogleDriveUserProfileAdapter(adapter);
	}

	@Bean
	WorkspaceUsageReportPort workspaceUsageReportPort(GoogleServiceAccountAdapter adapter) {
		return new GoogleWorkspaceUsageReportAdapter(adapter);
	}

	@Bean
	CloudQuotaLimitPort cloudQuotaLimitPort(GoogleServiceAccountAdapter adapter,
			CredentialStoragePort credentialStoragePort) {
		return new GoogleCloudQuotaLimitAdapter(adapter, credentialStoragePort);
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
	DriveUsageQuotaUseCase driveUsageQuotaUseCase(DriveUsageQuotaPort port) {
		return new DriveUsageQuotaService(port);
	}

	@Bean
	DriveUserProfileUseCase driveUserProfileUseCase(DriveUserProfilePort port) {
		return new DriveUserProfileService(port);
	}

	@Bean
	WorkspaceUsageReportUseCase workspaceUsageReportUseCase(WorkspaceUsageReportPort port) {
		return new WorkspaceUsageReportService(port);
	}

	@Bean
	CloudQuotaLimitUseCase cloudQuotaLimitUseCase(CloudQuotaLimitPort port) {
		return new CloudQuotaLimitService(port);
	}
}
