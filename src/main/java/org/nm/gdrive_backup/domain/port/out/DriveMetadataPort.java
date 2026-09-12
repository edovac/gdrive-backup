package org.nm.gdrive_backup.domain.port.out;

import java.util.List;

import org.nm.gdrive_backup.domain.model.StoredDrive;

public interface DriveMetadataPort {

	List<StoredDrive> findAll();

	void save(StoredDrive drive);
}