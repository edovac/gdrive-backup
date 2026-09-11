package org.nm.gdrive_backup.adapter.out.google;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.auth.http.HttpCredentialsAdapter;
import org.nm.gdrive_backup.domain.model.DriveItem;
import org.nm.gdrive_backup.domain.model.AvailableDrive;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.port.out.DriveReadPort;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

public class GoogleDriveAdapter implements DriveReadPort {

	private static final String FOLDER_MIME_TYPE = "application/vnd.google-apps.folder";
	private static final String DEFAULT_PARENT_ID = "root";

	private final GoogleServiceAccountAdapter credentialAdapter;

	public GoogleDriveAdapter(GoogleServiceAccountAdapter credentialAdapter) {
		this.credentialAdapter = credentialAdapter;
	}

	@Override
	public List<AvailableDrive> listAvailableDrives(ServiceAccountAccess access) {
		try {
			Drive.Drives.List request = drive(access).drives().list()
					.setPageSize(100)
					.setFields("drives(id,name),nextPageToken");
			List<AvailableDrive> drives = request.execute().getDrives().stream()
					.map(sharedDrive -> new AvailableDrive(sharedDrive.getId(), sharedDrive.getName(), true))
					.toList();
			return java.util.stream.Stream.concat(
					java.util.stream.Stream.of(new AvailableDrive("root", "My Drive", false)),
					drives.stream()).toList();
		} catch (IOException | GeneralSecurityException exception) {
			throw new GoogleDriveException("Unable to list available drives", exception);
		}
	}

	@Override
	public List<DriveItem> listMyDriveItems(ServiceAccountAccess access, String parentId) {
		String effectiveParentId = parentId == null || parentId.isBlank() ? DEFAULT_PARENT_ID : parentId;
		try {
			Drive.Files.List request = drive(access).files().list()
					.setQ("'" + effectiveParentId + "' in parents")
					.setSpaces("drive")
					.setFields("files(id,name,mimeType,driveId,trashed),nextPageToken")
					.setSupportsAllDrives(true)
					.setIncludeItemsFromAllDrives(true);
			return mapFiles(request.execute().getFiles());
		} catch (IOException | GeneralSecurityException exception) {
			throw new GoogleDriveException("Unable to list My Drive items", exception);
		}
	}

	@Override
	public List<DriveItem> listSharedDriveItems(ServiceAccountAccess access, String driveId) {
		if (driveId == null || driveId.isBlank()) {
			throw new IllegalArgumentException("driveId must not be blank");
		}
		try {
			Drive.Files.List request = drive(access).files().list()
					.setQ("'" + driveId + "' in parents")
					.setCorpora("drive")
					.setDriveId(driveId)
					.setSpaces("drive")
					.setFields("files(id,name,mimeType,driveId,trashed),nextPageToken")
					.setSupportsAllDrives(true)
					.setIncludeItemsFromAllDrives(true);
			return mapFiles(request.execute().getFiles());
		} catch (IOException | GeneralSecurityException exception) {
			throw new GoogleDriveException("Unable to list Shared Drive items", exception);
		}
	}

	private Drive drive(ServiceAccountAccess access) throws IOException, GeneralSecurityException {
		if (access == null) {
			throw new IllegalArgumentException("access must not be null");
		}
		HttpRequestInitializer initializer = new HttpCredentialsAdapter(credentialAdapter.credentialsFor(access));
		return new Drive.Builder(
				GoogleNetHttpTransport.newTrustedTransport(),
				GsonFactory.getDefaultInstance(),
				initializer)
				.setApplicationName("gdrive-backup")
				.build();
	}

	private static List<DriveItem> mapFiles(List<File> files) {
		if (files == null) {
			return List.of();
		}
		return files.stream()
				.map(file -> new DriveItem(
						file.getId(), file.getName(), file.getMimeType(), file.getDriveId(),
						FOLDER_MIME_TYPE.equals(file.getMimeType()), Boolean.TRUE.equals(file.getTrashed())))
				.toList();
	}
}