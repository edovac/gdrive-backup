package org.nm.gdrive_backup.adapter.out.google;

import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.Change;
import com.google.api.services.drive.model.File;
import com.google.auth.http.HttpCredentialsAdapter;
import org.nm.gdrive_backup.domain.model.DriveItem;
import org.nm.gdrive_backup.domain.model.AvailableDrive;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.DriveScopeType;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.DriveChange;
import org.nm.gdrive_backup.domain.model.DriveChangePage;
import org.nm.gdrive_backup.domain.model.DriveExportLimitException;
import org.nm.gdrive_backup.domain.model.PersonalDriveContent;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.model.StaleDrivePageTokenException;
import org.nm.gdrive_backup.domain.port.out.DriveChangePort;
import org.nm.gdrive_backup.domain.port.out.DriveContentPort;
import org.nm.gdrive_backup.domain.port.out.DriveFileListingPort;
import org.nm.gdrive_backup.domain.port.out.DriveReadPort;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class GoogleDriveAdapter implements DriveReadPort, DriveChangePort, DriveContentPort, DriveFileListingPort {

	private static final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();

	private static final String FOLDER_MIME_TYPE = "application/vnd.google-apps.folder";
	private static final String DEFAULT_PARENT_ID = "root";

	private final GoogleServiceAccountAdapter credentialAdapter;
	private final DriveRetry retry = DriveRetry.standard();

	public GoogleDriveAdapter(GoogleServiceAccountAdapter credentialAdapter) {
		this.credentialAdapter = credentialAdapter;
	}

	@Override
	public List<AvailableDrive> listAvailableDrives(ServiceAccountAccess access) {
		try {
			Drive.Drives.List request = drive(access).drives().list()
					.setPageSize(100)
					.setFields("drives(id,name),nextPageToken");
			List<AvailableDrive> drives = retry.call(request::execute).getDrives().stream()
					.map(sharedDrive -> new AvailableDrive(sharedDrive.getId(), sharedDrive.getName(), true))
					.toList();
			return java.util.stream.Stream.concat(
					java.util.stream.Stream.of(new AvailableDrive("root", "My Drive", false)),
					drives.stream()).toList();
		} catch (IOException exception) {
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
			return mapFiles(retry.call(request::execute).getFiles());
		} catch (IOException exception) {
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
			return mapFiles(retry.call(request::execute).getFiles());
		} catch (IOException exception) {
			throw new GoogleDriveException("Unable to list Shared Drive items", exception);
		}
	}

	@Override
	public String getStartPageToken(ServiceAccountAccess access, DriveScope scope) {
		try {
			Drive.Changes.GetStartPageToken request = drive(access).changes().getStartPageToken()
					.setSupportsAllDrives(true);
			configureDriveScope(request, scope);
			return retry.call(request::execute).getStartPageToken();
		} catch (IOException exception) {
			throw new GoogleDriveException("Unable to get Drive change start token", exception);
		}
	}

	@Override
	public DriveChangePage listChanges(ServiceAccountAccess access, DriveScope scope, String pageToken,
			PersonalDriveContent content) {
		if (pageToken == null || pageToken.isBlank()) {
			throw new IllegalArgumentException("pageToken must not be blank");
		}
		try {
			Drive.Changes.List request = drive(access).changes().list(pageToken)
					.setPageSize(1000)
					.setSpaces("drive")
					.setSupportsAllDrives(true)
					.setIncludeItemsFromAllDrives(true)
					.setFields("changes(fileId,removed,file(id,name,parents,driveId,mimeType,trashed,headRevisionId,version,size,"
							+ "ownedByMe)),nextPageToken,newStartPageToken");
			configureDriveScope(request, scope);
			var response = retry.call(request::execute);
			List<DriveChange> changes = response.getChanges() == null ? List.of() : response.getChanges().stream()
				.map(change -> mapChange(change, scope, content))
				.toList();
			return new DriveChangePage(changes, response.getNextPageToken(), response.getNewStartPageToken());
		} catch (GoogleJsonResponseException exception) {
			if (exception.getStatusCode() == 410) {
				throw new StaleDrivePageTokenException("Drive change page token has expired", exception);
			}
			throw new GoogleDriveException("Unable to list Drive changes", exception);
		} catch (IOException exception) {
			throw new GoogleDriveException("Unable to list Drive changes", exception);
		}
	}

	@Override
	public List<StoredFile> listAllFiles(ServiceAccountAccess access, DriveScope scope, PersonalDriveContent content) {
		boolean ownedOnly = ownedOnly(scope, content);
		try {
			List<StoredFile> files = new java.util.ArrayList<>();
			String pageToken = null;
			do {
				Drive.Files.List request = drive(access).files().list()
						.setQ(listQuery(scope, content))
						.setPageSize(1000)
						.setPageToken(pageToken)
						.setSpaces("drive")
						.setSupportsAllDrives(true)
						// Shared Drive items have no owner, so owning them is never the reason to include them.
						.setIncludeItemsFromAllDrives(!ownedOnly)
						.setFields("files(id,name,parents,driveId,mimeType,trashed,headRevisionId,version,size),nextPageToken");
				configureFileScope(request, scope);
				var response = retry.call(request::execute);
				if (response.getFiles() != null) {
					files.addAll(response.getFiles().stream()
							.map(file -> mapStoredFile(file, scope.key())).toList());
				}
				pageToken = response.getNextPageToken();
			} while (pageToken != null && !pageToken.isBlank());
			return files;
		} catch (IOException exception) {
			throw new GoogleDriveException("Unable to list all Drive files", exception);
		}
	}

	@Override
	public InputStream download(ServiceAccountAccess access, String fileId) throws IOException {
		if (fileId == null || fileId.isBlank()) {
			throw new IllegalArgumentException("fileId must not be blank");
		}
		return retry.call(() -> drive(access).files().get(fileId)
				.setSupportsAllDrives(true)
				.executeMediaAsInputStream());
	}

	@Override
	public InputStream export(ServiceAccountAccess access, String fileId, String exportMimeType) throws IOException {
		if (fileId == null || fileId.isBlank() || exportMimeType == null || exportMimeType.isBlank()) {
			throw new IllegalArgumentException("fileId and exportMimeType must not be blank");
		}
		try {
			return retry.call(() -> drive(access).files().export(fileId, exportMimeType).executeMediaAsInputStream());
		} catch (GoogleJsonResponseException exception) {
			if (isExportLimitExceeded(exception)) {
				throw new DriveExportLimitException("Google export exceeds the supported size limit", exception);
			}
			throw exception;
		}
	}

	private static boolean isExportLimitExceeded(GoogleJsonResponseException exception) {
		String message = exception.getDetails() == null ? exception.getMessage() : exception.getDetails().getMessage();
		if (message == null) {
			return false;
		}
		String normalized = message.toLowerCase(java.util.Locale.ROOT);
		return normalized.contains("10 mb") || normalized.contains("10mb")
				|| normalized.contains("maximum allowed size") || normalized.contains("export size");
	}

	private static void configureDriveScope(Drive.Changes.GetStartPageToken request, DriveScope scope) {
		if (scope.type() == DriveScopeType.SHARED_DRIVE) {
			request.setDriveId(scope.key());
		}
	}

	private static void configureDriveScope(Drive.Changes.List request, DriveScope scope) {
		if (scope.type() == DriveScopeType.SHARED_DRIVE) {
			request.setDriveId(scope.key());
		}
	}

	private static void configureFileScope(Drive.Files.List request, DriveScope scope) {
		if (scope.type() == DriveScopeType.SHARED_DRIVE) {
			request.setCorpora("drive").setDriveId(scope.key());
		}
	}

	/** A personal drive otherwise lists every file the user can open, including those others shared with them. */
	static boolean ownedOnly(DriveScope scope, PersonalDriveContent content) {
		return scope.type() == DriveScopeType.PERSONAL && content == PersonalDriveContent.OWNED_ONLY;
	}

	static String listQuery(DriveScope scope, PersonalDriveContent content) {
		return ownedOnly(scope, content) ? "trashed = false and 'me' in owners" : "trashed = false";
	}

	/**
	 * The change feed takes no query, so a personal drive backing up owned files only drops the rest here: a file
	 * the user does not own, or one in a Shared Drive, is reported as out of scope.
	 */
	static DriveChange mapChange(Change change, DriveScope scope, PersonalDriveContent content) {
		boolean removed = Boolean.TRUE.equals(change.getRemoved());
		File file = change.getFile();
		if (!removed && file != null && ownedOnly(scope, content)
				&& (!Boolean.TRUE.equals(file.getOwnedByMe()) || file.getDriveId() != null)) {
			return DriveChange.outOfScope(change.getFileId());
		}
		return new DriveChange(change.getFileId(), removed, mapStoredFile(file, scope.key()));
	}

	/**
	 * {@code headRevisionId} of the resulting StoredFile is the file's content revision marker: see {@link #contentRevisionOf}.
	 * {@code sizeBytes} is Drive's {@code size}, which only ordinary files have; it is {@code null} for Google-native files.
	 */
	static StoredFile mapStoredFile(File file, String ownerScope) {
		if (file == null) {
			return null;
		}
		String parents = file.getParents() == null ? "" : String.join(",", file.getParents());
		return new StoredFile(file.getId(), ownerScope, file.getName(), parents, file.getDriveId(),
				file.getMimeType(), Boolean.TRUE.equals(file.getTrashed()), contentRevisionOf(file), null,
				file.getSize());
	}

	/**
	 * Drive only reports headRevisionId for binary files. Google-native files (Docs, Sheets, Slides) have none,
	 * so their monotonic {@code version} stands in for it; it also moves on metadata-only edits, which at worst
	 * re-exports a file that did not change.
	 */
	private static String contentRevisionOf(File file) {
		String headRevisionId = file.getHeadRevisionId();
		if (headRevisionId != null && !headRevisionId.isBlank()) {
			return headRevisionId;
		}
		return file.getVersion() == null ? null : "v" + file.getVersion();
	}

	private Drive drive(ServiceAccountAccess access) throws IOException {
		if (access == null) {
			throw new IllegalArgumentException("access must not be null");
		}
		HttpRequestInitializer initializer = new HttpCredentialsAdapter(credentialAdapter.credentialsFor(access));
		return new Drive.Builder(
				HTTP_TRANSPORT,
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
