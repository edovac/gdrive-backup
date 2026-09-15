package org.nm.gdrive_backup.domain.port.out;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

public interface CaptureStoragePort {

	Path store(String ownerScope, String fileId, String fileName, InputStream content) throws IOException;
}
