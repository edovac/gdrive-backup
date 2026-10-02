package org.nm.gdrive_backup.adapter.out.persistence;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.nm.gdrive_backup.domain.model.PersonalDriveContent;
import org.nm.gdrive_backup.domain.model.DriveChange;
import org.nm.gdrive_backup.domain.model.DriveChangePage;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.port.out.DriveChangePort;
import org.nm.gdrive_backup.domain.port.out.DriveContentPort;
import org.nm.gdrive_backup.domain.port.out.DriveFileListingPort;

/** A minimal in-memory Drive: files with content and revisions, plus a change log addressed by position. */
class FakeDrive implements DriveFileListingPort, DriveChangePort, DriveContentPort {

	static final String USER = "user@example.com";
	static final String FOLDER = "application/vnd.google-apps.folder";
	static final String PDF = "application/pdf";
	static final String DOC = "application/vnd.google-apps.document";


	private final Map<String, Node> nodes = new LinkedHashMap<>();
	private final List<DriveChange> log = new ArrayList<>();

	void create(String id, String name, String parents, String mime, String content) {
		nodes.put(id, new Node(id, name, parents, mime, content));
		touch(id);
	}

	void edit(String id, String content) {
		Node node = nodes.get(id);
		node.content = content;
		node.revision++;
		touch(id);
	}

	void rename(String id, String name) {
		nodes.get(id).name = name;
		touch(id);
	}

	void move(String id, String parents) {
		nodes.get(id).parents = parents;
		touch(id);
	}

	void trash(String id) {
		nodes.get(id).trashed = true;
		touch(id);
	}

	void untrash(String id) {
		nodes.get(id).trashed = false;
		touch(id);
	}

	void delete(String id) {
		nodes.remove(id);
		log.add(new DriveChange(id, true, null));
	}

	private void touch(String id) {
		log.add(new DriveChange(id, false, nodes.get(id).toStoredFile()));
	}

	@Override
	public List<StoredFile> listAllFiles(ServiceAccountAccess access, DriveScope scope, PersonalDriveContent content) {
		return nodes.values().stream().filter(node -> !node.trashed).map(Node::toStoredFile).toList();
	}

	@Override
	public String getStartPageToken(ServiceAccountAccess access, DriveScope scope) {
		return Integer.toString(log.size());
	}

	@Override
	public DriveChangePage listChanges(ServiceAccountAccess access, DriveScope scope, String pageToken,
			PersonalDriveContent content) {
		int from = Integer.parseInt(pageToken);
		return new DriveChangePage(new ArrayList<>(log.subList(from, log.size())), null,
				Integer.toString(log.size()));
	}

	@Override
	public InputStream download(ServiceAccountAccess access, String fileId) {
		return new ByteArrayInputStream(nodes.get(fileId).content.getBytes(StandardCharsets.UTF_8));
	}

	@Override
	public InputStream export(ServiceAccountAccess access, String fileId, String exportMimeType) {
		return download(access, fileId);
	}

	private static final class Node {
		final String id;
		String name;
		String parents;
		final String mime;
		String content;
		boolean trashed;
		int revision = 1;

		Node(String id, String name, String parents, String mime, String content) {
			this.id = id;
			this.name = name;
			this.parents = parents;
			this.mime = mime;
			this.content = content;
		}

		StoredFile toStoredFile() {
			String head = mime.equals(FOLDER) ? null : mime.equals(DOC) ? "v" + revision : "rev-" + revision;
			return new StoredFile(id, USER, name, parents, null, mime, trashed, head, null);
		}
	}
}
