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
import org.nm.gdrive_backup.domain.model.DriveChange;
import org.nm.gdrive_backup.domain.model.DriveChangePage;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.port.out.DriveChangePort;
import org.nm.gdrive_backup.domain.port.out.DriveReadPort;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

public class GoogleDriveAdapter implements DriveReadPort, DriveChangePort {

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

	@Override
	public String getStartPageToken(ServiceAccountAccess access, String scopeKey) {
		try {
			Drive.Changes.GetStartPageToken request = drive(access).changes().getStartPageToken()
					.setSupportsAllDrives(true);
			configureDriveScope(request, scopeKey);
			return request.execute().getStartPageToken();
		} catch (IOException | GeneralSecurityException exception) {
			throw new GoogleDriveException("Unable to get Drive change start token", exception);
		}
	}

	@Override
	public DriveChangePage listChanges(ServiceAccountAccess access, String scopeKey, String pageToken) {
		if (pageToken == null || pageToken.isBlank()) {
			throw new IllegalArgumentException("pageToken must not be blank");
		}
		try {
			Drive.Changes.List request = drive(access).changes().list(pageToken)
					.setPageSize(1000)
					.setSpaces("drive")
					.setSupportsAllDrives(true)
					.setIncludeItemsFromAllDrives(true)
					.setFields("changes(fileId,removed,file(id,name,parents,driveId,mimeType,trashed,headRevisionId)),"
							+ "nextPageToken,newStartPageToken");
			configureDriveScope(request, scopeKey);
			var response = request.execute();
			List<DriveChange> changes = response.getChanges() == null ? List.of() : response.getChanges().stream()
				.map(change -> new DriveChange(
						change.getFileId(),
						Boolean.TRUE.equals(change.getRemoved()),
						mapStoredFile(change.getFile(), access.impersonatedUserEmail())))
				.toList();
			return new DriveChangePage(changes, response.getNextPageToken(), response.getNewStartPageToken());
		} catch (IOException | GeneralSecurityException exception) {
			throw new GoogleDriveException("Unable to list Drive changes", exception);
		}
	}

	private static void configureDriveScope(Drive.Changes.GetStartPageToken request, String scopeKey) {
		if (isSharedDriveScope(scopeKey)) {
			request.setDriveId(scopeKey);
		}
	}

	private static void configureDriveScope(Drive.Changes.List request, String scopeKey) {
		if (isSharedDriveScope(scopeKey)) {
			request.setDriveId(scopeKey);
		}
	}

	private static boolean isSharedDriveScope(String scopeKey) {
		return scopeKey != null && !scopeKey.isBlank() && !scopeKey.contains("@");
	}

	private static StoredFile mapStoredFile(File file, String ownerScope) {
		if (file == null) {
			return null;
		}
		String parents = file.getParents() == null ? "" : String.join(",", file.getParents());
		return new StoredFile(file.getId(), ownerScope, file.getName(), parents, file.getDriveId(),
				file.getMimeType(), Boolean.TRUE.equals(file.getTrashed()), file.getHeadRevisionId(), null);
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

	static List<DriveItem> mapFiles(List<File> files) {
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