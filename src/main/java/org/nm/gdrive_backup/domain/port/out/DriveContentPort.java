package org.nm.gdrive_backup.domain.port.out;

import java.io.IOException;
import java.io.InputStream;

import org.nm.gdrive_backup.domain.model.ServiceAccountAccess;

public interface DriveContentPort {

	InputStream download(ServiceAccountAccess access, String fileId) throws IOException;

	InputStream export(ServiceAccountAccess access, String fileId, String exportMimeType) throws IOException;
}