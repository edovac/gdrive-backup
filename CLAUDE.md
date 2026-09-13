# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Shared agent rules

@AGENTS.md

AGENTS.md is the canonical source for architecture rules, build conventions, and credential handling; keep it authoritative rather than duplicating its rules here.

## Use cases and project tracking

[gdrive-backup-app.md](gdrive-backup-app.md) is both the requirements spec and the working roadmap:

- **Implementation progress** (`[x]` / `[-]` / `[ ]`) and the **Approved prioritization** (P0–P3 plus the implementation sequence) decide what to work on next.
- When a vertical slice is completed or advanced, update its status marker (and progress note) and bump the `Last reviewed` date.
- The sections below the roadmap (core requirements, auth model, data model, sync algorithm, export rules, storage layout, UI) define expected behavior. Check new work against them and flag conflicts instead of silently diverging.

[google-admin-console-setup.md](google-admin-console-setup.md) documents the manual domain-wide delegation setup.

## Commands

```bash
./mvnw test                                        # unit tests (*Test, Surefire)
./mvnw -Dtest=DriveBackupServiceTest test          # single test class
./mvnw -Dtest=DriveBackupServiceTest#methodName test
./mvnw -Dtest=HexagonalArchitectureTest test       # ArchUnit boundary rules
./mvnw verify                                      # also runs *IT (Failsafe)
./mvnw spring-boot:run                             # launch the JavaFX app
```

Integration tests hit real Google accounts. They are skipped unless the matching system property is set, and they also read credentials from env vars:

```bash
./mvnw verify -Dgoogle.service-account.integration=true   # service account + Workspace directory ITs
./mvnw verify -Dgoogle.oauth.integration=true             # interactive OAuth IT
```

### Runtime configuration

Configuration comes from environment variables bound through Spring relaxed binding. The gitignored `.env` holds local values. Spring Boot does not load `.env` itself, so export it first (`set -a; source .env; set +a`).

| Env var | Property | Effect when unset |
|---|---|---|
| `GOOGLE_OAUTH_CLIENT_SECRETS` | `google.oauth.client-secrets` | Sign-in fails with a "not configured" message |
| `GOOGLE_SERVICE_ACCOUNT_KEY` | `google.service-account.key` | All Google-backed beans are replaced by fallbacks (see below) |
| `GOOGLE_IMPERSONATED_USER` | `google.drive.preview.user-email` | No user listing and no default preview user; this admin identity is impersonated for Admin SDK calls |
| `GOOGLE_CLOUD_PROJECT_ID` | `google.service-account.project-id` | Cloud quota limits unavailable |
| `GOOGLE_BACKUP_ROOT` | `google.backup.root` | `~/.gdrive-backup/backupRoot` |
| `GDRIVE_BACKUP_DATABASE` | `gdrive.backup.database` | `~/.gdrive-backup/backup.db` |

## Architecture: how the pieces connect

**Startup and UI wiring.** `GdriveBackupApplication.main` starts the Spring context (non-headless) and pulls the use-case beans out of it. It passes them into static setters on `JavaFxApplication`, then calls `Application.launch`. `JavaFxApplication` is currently the only inbound adapter: the whole UI is built in code in that one class, with no FXML and no `adapter.in` package yet. To expose a new use case in the UI, change both classes. UI work runs off the FX thread with `CompletableFuture.supplyAsync(...)` and returns to it with `Platform.runLater(...)`.

**Bean composition.** Domain services are plain Java with no Spring annotations. They are built in `@Bean` methods in `configuration/*Configuration`. The SQLite adapters are the exception: they are `@Component`s. Google-backed beans are guarded by `@ConditionalOnExpression` on `google.service-account.key`. When the key is missing, `ServiceAccountConfiguration` registers a fallback lambda for each use case/port that throws `GoogleOAuthException` with a configuration hint. This keeps the app and `GdriveBackupApplicationTest` (a plain `@SpringBootTest`) booting with no credentials; new Google-backed beans should follow the same pattern. `GoogleDriveAdapter` implements several Drive ports, and each port gets its own bean, with `@Qualifier`/`@Primary` choosing between them.

**Access flow.** Every Drive/Admin operation starts with `ServiceAccountAuthenticationUseCase.authenticateAs(userEmail)`, which returns an opaque `ServiceAccountAccess`. That value is passed into the use case or port, and the adapter turns it into delegated Google credentials. The admin OAuth session only unlocks the UI.

**Backup/sync flow.**
- `DriveBackupService` is the entry point: it runs `InitialDriveSyncService` when the scope has no `sync_state` row and `DriveChangeSyncService` otherwise.
- `StaleDrivePageTokenException` makes it delete the scope's sync state and re-run the initial sync.
- Both sync services use `FileContentBackupService`, which downloads or exports content (`DriveContentPort`), writes it to disk (`VersionStoragePort` → `LocalVersionStorageAdapter`), and records it (`FileVersionPort`).
- `DriveExportLimitException` triggers the PDF export fallback.

**Persistence.**
- `SqliteDatabase` opens a new JDBC connection for each operation.
- The schema is `src/main/resources/db/schema.sql`, applied at startup by an `ApplicationRunner` in `DatabaseConfiguration`.
- It only uses `CREATE TABLE/INDEX IF NOT EXISTS` and there is no migration tool, so changes to an existing table will not reach databases that already exist.

## Repository notes

- `build/` (gitignored Gradle leftovers) is not part of the Maven build; ignore it.
