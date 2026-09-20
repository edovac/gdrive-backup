package org.nm.gdrive_backup.domain.model;

/** Outcome of a merge; {@code archive} is null when the admin cancelled it, in which case nothing was written. */
public record MergeResult(Archive archive, boolean cancelled) {
}
