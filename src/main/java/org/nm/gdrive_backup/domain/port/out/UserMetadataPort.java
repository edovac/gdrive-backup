package org.nm.gdrive_backup.domain.port.out;

import java.util.List;
import java.util.Optional;

import org.nm.gdrive_backup.domain.model.StoredUser;

public interface UserMetadataPort {

	Optional<StoredUser> findByEmail(String email);

	List<StoredUser> findAll();

	void save(StoredUser user);
}