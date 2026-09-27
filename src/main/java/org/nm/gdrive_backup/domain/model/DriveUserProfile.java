package org.nm.gdrive_backup.domain.model;

/**
 * The signed-in admin's identity as Drive reports it for the impersonated user, shown next to the header avatar.
 * {@code displayName} and {@code photoUrl} are nullable: Drive's {@code about.user} doesn't always carry them.
 */
public record DriveUserProfile(String email, String displayName, String photoUrl) {
}
