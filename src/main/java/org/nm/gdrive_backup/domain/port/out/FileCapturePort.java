package org.nm.gdrive_backup.domain.port.out;

import java.util.List;

import org.nm.gdrive_backup.domain.model.FileCapture;

public interface FileCapturePort {

	FileCapture save(FileCapture capture);

	List<FileCapture> findByFileId(String fileId);
}
