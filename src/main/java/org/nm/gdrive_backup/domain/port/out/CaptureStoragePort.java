package org.nm.gdrive_backup.domain.port.out;

import java.io.IOException;
import java.io.InputStream;

import org.nm.gdrive_backup.domain.model.StoredCapture;

public interface CaptureStoragePort {

	/** Stores content and returns where it landed, relative to the store's root, and its size. */
	StoredCapture store(String ownerScope, String fileId, String fileName, InputStream content) throws IOException;
}
