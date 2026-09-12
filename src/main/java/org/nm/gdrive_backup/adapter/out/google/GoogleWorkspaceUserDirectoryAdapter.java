package org.nm.gdrive_backup.adapter.out.google;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Comparator;
import java.util.List;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.directory.Directory;
import com.google.api.services.directory.model.User;
import com.google.api.services.directory.model.Users;
import com.google.auth.http.HttpCredentialsAdapter;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.WorkspaceUser;
import org.nm.gdrive_backup.domain.port.out.WorkspaceUserDirectoryPort;

public class GoogleWorkspaceUserDirectoryAdapter implements WorkspaceUserDirectoryPort {

	private final GoogleServiceAccountAdapter credentialAdapter;

	public GoogleWorkspaceUserDirectoryAdapter(GoogleServiceAccountAdapter credentialAdapter) {
		this.credentialAdapter = credentialAdapter;
	}

	@Override
	public List<WorkspaceUser> listUsers(ServiceAccountAccess access) {
		try {
			Directory directory = directory(access);
			Directory.Users.List request = directory.users().list()
					.setCustomer("my_customer")
					.setOrderBy("email")
					.setProjection("basic")
					.setMaxResults(500)
					.setFields("users(primaryEmail,name/fullName),nextPageToken");
			Users response = request.execute();
			if (response == null || response.getUsers() == null) {
				return List.of();
			}
			return response.getUsers().stream()
					.map(this::toWorkspaceUser)
					.filter(user -> user != null)
					.sorted(Comparator.comparing(WorkspaceUser::email))
					.toList();
		} catch (IOException | GeneralSecurityException exception) {
			throw new GoogleDriveException("Unable to list Workspace users", exception);
		}
	}

	private Directory directory(ServiceAccountAccess access)
			throws IOException, GeneralSecurityException {
		if (access == null) {
			throw new IllegalArgumentException("access must not be null");
		}
		HttpRequestInitializer initializer = new HttpCredentialsAdapter(
				credentialAdapter.credentialsFor(access));
		return new Directory.Builder(
				GoogleNetHttpTransport.newTrustedTransport(),
				GsonFactory.getDefaultInstance(),
				initializer)
				.setApplicationName("gdrive-backup")
				.build();
	}

	private WorkspaceUser toWorkspaceUser(User user) {
		if (user == null || user.getPrimaryEmail() == null || user.getPrimaryEmail().isBlank()) {
			return null;
		}
		String displayName = user.getName() != null && user.getName().getFullName() != null
				? user.getName().getFullName()
				: user.getPrimaryEmail();
		return new WorkspaceUser(user.getPrimaryEmail(), displayName);
	}
}
