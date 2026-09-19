package org.nm.gdrive_backup.domain.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.nm.gdrive_backup.domain.model.Archive;
import org.nm.gdrive_backup.domain.model.ArchiveChainException;
import org.nm.gdrive_backup.domain.model.ArchiveManifest;
import org.nm.gdrive_backup.domain.model.ArchiveManifest.ManifestFile;
import org.nm.gdrive_backup.domain.model.ArchiveManifest.ManifestSource;
import org.nm.gdrive_backup.domain.model.ArchiveReader;
import org.nm.gdrive_backup.domain.model.ArchiveSession;
import org.nm.gdrive_backup.domain.model.DriveScope;
import org.nm.gdrive_backup.domain.model.PendingCommit;
import org.nm.gdrive_backup.domain.model.RevisionMode;
import org.nm.gdrive_backup.domain.model.StoredFile;
import org.nm.gdrive_backup.domain.port.in.ArchiveMergeUseCase;
import org.nm.gdrive_backup.domain.port.out.ArchivePort;
import org.nm.gdrive_backup.domain.port.out.ArchiveReaderPort;
import org.nm.gdrive_backup.domain.port.out.ArchiveSessionPort;
import org.nm.gdrive_backup.domain.port.out.SyncCommitPort;
import org.nm.gdrive_backup.domain.service.ChainFolder.FoldedFile;

/**
 * Builds a {@code MERGED_FULL} from a drive's current chain using only its archives: Drive, the file metadata
 * tables and the sync cursor are never touched. The chain's own manifests give every file's name and place; the
 * flat-tree rules are the same as a from-scratch full. The merged full becomes the chain's new root.
 */
public class ArchiveMergeService implements ArchiveMergeUseCase {

	private final ArchivePort archivePort;
	private final ArchiveReaderPort archiveReaderPort;
	private final ArchiveSessionPort archiveSessionPort;
	private final ArchiveRunPlanner archiveRunPlanner;
	private final SyncCommitPort syncCommitPort;
	private final BackupActivity backupActivity;

	public ArchiveMergeService(ArchivePort archivePort, ArchiveReaderPort archiveReaderPort,
			ArchiveSessionPort archiveSessionPort, ArchiveRunPlanner archiveRunPlanner, SyncCommitPort syncCommitPort,
			BackupActivity backupActivity) {
		this.archivePort = archivePort;
		this.archiveReaderPort = archiveReaderPort;
		this.archiveSessionPort = archiveSessionPort;
		this.archiveRunPlanner = archiveRunPlanner;
		this.syncCommitPort = syncCommitPort;
		this.backupActivity = backupActivity;
	}

	@Override
	public Archive merge(DriveScope scope, String scopeDisplayNameOrNull) {
		return backupActivity.duringBackup(() -> mergeChain(scope, scopeDisplayNameOrNull));
	}

	private Archive mergeChain(DriveScope scope, String scopeDisplayNameOrNull) {
		List<Archive> chain = ArchiveChainResolver.currentChain(archivePort.findByScopeKey(scope.key()));
		List<ArchiveReader> readers = new ArrayList<>();
		try {
			for (Archive archive : chain) {
				readers.add(open(archive));
			}
			List<ArchiveManifest> manifests = readers.stream().map(ArchiveReader::manifest).toList();
			ChainVerifier.verify(scope, chain, manifests);
			Map<String, FoldedFile> folded = new TreeMap<>(ChainFolder.fold(manifests));

			Map<String, StoredFile> named = new TreeMap<>();
			folded.forEach((fileId, file) -> named.put(fileId, toStoredFile(scope, file)));
			FlatTreePathResolver resolver = new FlatTreePathResolver(named);

			ArchiveRunPlanner.Plan plan = archiveRunPlanner.planMergedFull(scope, scopeDisplayNameOrNull);
			Archive tip = chain.getLast();
			try (ArchiveSession session = archiveSessionPort.open(plan.relativeTargetPath())) {
				List<ManifestFile> records = new ArrayList<>();
				for (Map.Entry<String, FoldedFile> entry : folded.entrySet()) {
					records.add(writeFile(session, readers, chain, resolver, named.get(entry.getKey()), entry.getValue()));
				}
				Instant createdAt = Instant.now();
				List<ManifestSource> sources = chain.stream()
						.map(archive -> new ManifestSource(archive.sequenceNumber(), fileName(archive)))
						.toList();
				session.publish(new ArchiveManifest(scope, plan.mode(), RevisionMode.LATEST_ONLY,
						plan.sequenceNumber(), null, createdAt, null, tip.toPageToken(), sources, records, List.of()));
				Archive archive = plan.toArchive(createdAt, null, tip.toPageToken());
				return syncCommitPort.commit(new PendingCommit(archive, List.of(), List.of(), List.of(), null,
						chain.stream().map(Archive::id).toList()));
			} catch (IOException exception) {
				throw new IllegalStateException("Unable to write archive " + plan.relativeTargetPath(), exception);
			}
		} finally {
			readers.forEach(ArchiveReader::close);
		}
	}

	private ArchiveReader open(Archive archive) {
		try {
			return archiveReaderPort.open(archive.archivePath());
		} catch (IOException exception) {
			throw new ArchiveChainException("Archive " + archive.sequenceNumber() + " (" + archive.archivePath()
					+ ") cannot be read: " + exception.getMessage(), exception);
		}
	}

	/** Copies a file's bytes into the merged archive at its resolved path and returns its manifest record. */
	private static ManifestFile writeFile(ArchiveSession session, List<ArchiveReader> readers, List<Archive> chain,
			FlatTreePathResolver resolver, StoredFile named, FoldedFile file) throws IOException {
		ManifestFile metadata = file.metadata();
		ChainFolder.ContentSource source = file.content();
		if (source == null || metadata.trashed()) {
			return new ManifestFile(metadata.fileId(), false, metadata.name(), metadata.parents(), metadata.driveId(),
					metadata.mimeType(), metadata.trashed(), null, null, null, null);
		}
		String entryName = resolver.resolveEntryName(named);
		InputStream content;
		try {
			content = readers.get(source.archiveIndex()).openEntry(source.entry());
		} catch (IOException exception) {
			throw new ArchiveChainException("Cannot read " + source.entry() + " from archive "
					+ chain.get(source.archiveIndex()).sequenceNumber() + ": " + exception.getMessage(), exception);
		}
		long written;
		try (content) {
			written = session.writeEntry(entryName, content);
		}
		if (source.sizeBytes() != null && written != source.sizeBytes()) {
			throw new ArchiveChainException("Entry " + source.entry() + " in archive "
					+ chain.get(source.archiveIndex()).sequenceNumber() + " holds " + written + " bytes but its manifest "
					+ "says " + source.sizeBytes());
		}
		return new ManifestFile(metadata.fileId(), false, metadata.name(), metadata.parents(), metadata.driveId(),
				metadata.mimeType(), false, source.revisionId(), entryName, written, source.exportMimeType());
	}

	/** A StoredFile carrying the name the file gets in the tree: its name plus its export extension, if any. */
	private static StoredFile toStoredFile(DriveScope scope, FoldedFile file) {
		ManifestFile metadata = file.metadata();
		String name = metadata.name();
		if (file.content() != null) {
			String extension = FileContentStreamingService.extensionForExportMimeType(file.content().exportMimeType())
					.orElse(null);
			if (extension != null && !name.endsWith(extension)) {
				name = name + extension;
			}
		}
		return new StoredFile(metadata.fileId(), scope.key(), name, String.join(",", metadata.parents()),
				metadata.driveId(), metadata.mimeType(), metadata.trashed(), null, null);
	}

	private static String fileName(Archive archive) {
		String path = archive.archivePath();
		return path.substring(path.lastIndexOf('/') + 1);
	}
}
