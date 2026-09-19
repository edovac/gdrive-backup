package org.nm.gdrive_backup.domain.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.nm.gdrive_backup.domain.model.ArchiveManifest;
import org.nm.gdrive_backup.domain.model.ArchiveManifest.ManifestFile;

/**
 * Folds a chain's manifests, in order, into the drive's state as of the last one (see "Folding a chain" in the
 * requirements): the latest record for a file gives its metadata, the latest record that has an entry gives its
 * content, and a removed record deletes it.
 */
final class ChainFolder {

	private ChainFolder() {
	}

	/** Where a file's bytes live: the archive (by index in the chain) and entry, plus how it was captured. */
	record ContentSource(int archiveIndex, String entry, String revisionId, Long sizeBytes, String exportMimeType) {
	}

	record FoldedFile(ManifestFile metadata, ContentSource content) {
	}

	static Map<String, FoldedFile> fold(List<ArchiveManifest> inOrder) {
		Map<String, FoldedFile> state = new LinkedHashMap<>();
		for (int index = 0; index < inOrder.size(); index++) {
			for (ManifestFile record : inOrder.get(index).files()) {
				if (record.removed()) {
					state.remove(record.fileId());
					continue;
				}
				FoldedFile previous = state.get(record.fileId());
				ContentSource content = record.entry() != null
						? new ContentSource(index, record.entry(), record.revisionId(), record.sizeBytes(),
								record.exportMimeType())
						: previous == null ? null : previous.content();
				state.put(record.fileId(), new FoldedFile(record, content));
			}
		}
		return state;
	}
}
