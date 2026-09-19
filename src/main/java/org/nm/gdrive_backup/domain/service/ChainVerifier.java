package org.nm.gdrive_backup.domain.service;

import java.util.List;

import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveChainException;
import org.nm.gdrive_backup.domain.model.ArchiveManifest;
import org.nm.gdrive_backup.domain.model.ArchiveMode;
import org.nm.gdrive_backup.domain.model.DriveScope;

/**
 * Checks that what is inside each archive agrees with the database's record of it and with its neighbours.
 * Page tokens are deliberately not compared: a run that found nothing moves the cursor without writing an
 * archive, so an incremental's from-token legitimately differs from its predecessor's to-token.
 */
final class ChainVerifier {

	private ChainVerifier() {
	}

	static void verify(DriveScope scope, List<Archive> chain, List<ArchiveManifest> manifests) {
		for (int i = 0; i < chain.size(); i++) {
			Archive archive = chain.get(i);
			ArchiveManifest manifest = manifests.get(i);
			String label = "Archive " + archive.sequenceNumber();
			if (!scope.equals(manifest.scope())) {
				throw new ArchiveChainException(label + " belongs to " + manifest.scope() + ", not " + scope);
			}
			if (manifest.sequenceNumber() != archive.sequenceNumber() || manifest.mode() != archive.mode()) {
				throw new ArchiveChainException(label + " says it is " + manifest.mode() + " number "
						+ manifest.sequenceNumber() + " but the records say " + archive.mode() + " number "
						+ archive.sequenceNumber());
			}
			if (i == 0) {
				if (archive.mode() != ArchiveMode.FULL && archive.mode() != ArchiveMode.MERGED_FULL) {
					throw new ArchiveChainException(label + " starts the chain but is " + archive.mode());
				}
				if (manifest.baseSequenceNumber() != null) {
					throw new ArchiveChainException(label + " starts the chain but names a base archive");
				}
			} else {
				if (archive.mode() != ArchiveMode.INCREMENTAL) {
					throw new ArchiveChainException(label + " sits inside the chain but is " + archive.mode());
				}
				int expectedBase = chain.get(i - 1).sequenceNumber();
				if (manifest.baseSequenceNumber() == null || manifest.baseSequenceNumber() != expectedBase) {
					throw new ArchiveChainException(label + " chains onto archive " + manifest.baseSequenceNumber()
							+ " but the chain expects archive " + expectedBase);
				}
			}
		}
	}
}
