# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Shared agent rules

@AGENTS.md

AGENTS.md is the canonical source for architecture rules, build conventions, and credential handling; keep it authoritative rather than duplicating its rules here.

## Use cases and project tracking

Requirements and the implementation plan are kept in separate documents:

- [gdrive-backup-app.md](docs/gdrive-backup-app.md) is the requirements spec (core requirements, auth model, data model, sync algorithm, export rules, storage layout, UI, known limitations). It defines expected behavior. Check new work against it and flag conflicts instead of silently diverging.
- [plan.md](docs/plan.md) is the working roadmap. Its **Implementation progress** (`[x]` / `[-]` / `[ ]`) and **Approved prioritization** (P0–P3 plus the implementation sequence) decide what to work on next.
- When a vertical slice is completed or advanced, update its status marker (and progress note) in `plan.md` and bump its `Last reviewed` date. When the work changes expected behavior, update the requirements spec too.

[google-admin-console-setup.md](docs/google-admin-console-setup.md) documents the manual domain-wide delegation setup.

## Model selection

[claude-models.md](claude-models.md) maps each roadmap task to a Claude model. The user is on Claude Pro and uses **only models included in the plan**: Sonnet 5, Opus 5 (standard context) and Haiku 4.5. Never use or suggest options that need usage credits: Fable 5.1, fast mode, or Opus with 1M context. When spawning subagents, pick the model that document assigns to the task; use Haiku for search and reading.

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

### Releasing

Pushing a `v*` tag runs `.github/workflows/release.yml`. It runs the tests, builds the Windows app-image, which bundles the `.exe` and the JRE, and publishes it as a zip on a GitHub Release. The tag must be numeric (`vMAJOR[.MINOR[.PATCH]]`) because jpackage rejects other versions.

```bash
git tag v0.1.0 && git push origin v0.1.0
./mvnw.cmd -P windows-package -DskipTests -Dapp.version=0.1.0 package   # local build of the same app-image
```

### Runtime configuration

The app reads no credentials from environment variables or files at runtime. The admin imports them in the **Settings** dialog (`SettingsPanel`, reachable signed in or out) through `CredentialConfigurationUseCase`, which validates each file with the Google parsers that later use it and stores it through `CredentialStoragePort`. On Windows that is `WindowsCredentialManagerAdapter` (Windows Credential Manager, each value chunked under `gdrive-backup/<name>/chunk-N` plus a `manifest` written last); elsewhere, including Linux CI, `InMemoryCredentialStorageAdapter`, which forgets everything on exit. Import and clear take the `BackupActivity` write lock, so they fail while a backup runs. The diagram in the spec's **Security configuration and storage** section shows the whole picture.

| Stored value | Read by | Effect when missing |
|---|---|---|
| OAuth client secrets JSON | `GoogleOAuthClientAdapter` at sign-in | Sign-in fails with a "not configured" message |
| Service-account key JSON | `GoogleServiceAccountAdapter` on each `authenticateAs` | Every Drive/Admin call throws `GoogleOAuthException` pointing to Settings |
| Cloud project id | `GoogleCloudQuotaLimitAdapter` | Cloud quota limits unavailable |

OAuth and delegated access tokens are held in memory only (`MemoryDataStoreFactory`); the admin signs in on every launch.

The `*IT` tests are the exception: they read `GOOGLE_SERVICE_ACCOUNT_KEY`, `GOOGLE_IMPERSONATED_USER` and `GOOGLE_OAUTH_CLIENT_SECRETS` from the environment. The gitignored `.env` can hold them; export it first (`set -a; source .env; set +a`).

There is no env var for the impersonated/preview user. OAuth login requests `openid` and the email scope alongside `drive.readonly`; the admin's email comes back in the ID token and is impersonated for Admin SDK calls and used as the default preview user (see `GoogleOAuthClientAdapter`, `GoogleLoginSession.userEmail()`).

The backup location is deliberately not configurable through the environment. Every launch starts with `~/.gdrive-backup` as the single root for the backup history database (`backup.db`), the archive output, and the admin changes it for the current session in the UI through `BackupLocationUseCase`. There is no separate database-location setting — `backup.db` always lives inside the chosen root.

## Architecture: how the pieces connect

**Startup and UI wiring.** `GdriveBackupApplication.main` starts the Spring context (non-headless) and pulls the use-case beans out of it. It passes them into static setters on `JavaFxApplication`, then calls `Application.launch`. `JavaFxApplication` builds most of the UI in code in that one class, with no FXML. The panels live in the `adapter.in.javafx` package (`ArchiveManagerPanel`, `FileHistoryPanel`, `TechnicalInfoPanel`, `SessionHeaderPanel`, `OperationProgressPanel`), each with its JavaFX-free wording in a matching `*Text` class (e.g. `ArchiveManagerText`); `JavaFxApplication` only assembles them into the header and the Backup / Archives / History / Technical info tabs. New UI should follow that pattern instead of growing `JavaFxApplication`. To expose a new use case in the UI, pass it in through `GdriveBackupApplication` and a static setter on `JavaFxApplication`. Long operations from the panel reuse the shared progress panel through two hooks (start and finish) the application supplies. UI work runs off the FX thread with `CompletableFuture.supplyAsync(...)` and returns to it with `Platform.runLater(...)`.

**Bean composition.** Domain services are plain Java with no Spring annotations. They are built in `@Bean` methods in `configuration/*Configuration`. The SQLite adapters are the exception: they are `@Component`s. Google-backed beans are always registered and read their credentials lazily from `CredentialStoragePort` on each call, throwing `GoogleOAuthException` with a "configure it in Settings" hint when a value is missing. Nothing is read at startup, so the app and `GdriveBackupApplicationTest` (a plain `@SpringBootTest`) boot with no credentials, and a credential imported in Settings takes effect without a restart; new Google-backed beans should follow the same pattern. `GoogleDriveAdapter` implements several Drive ports, and each port gets its own bean, with `@Qualifier`/`@Primary` choosing between them.

**Access flow.** Every Drive/Admin operation starts with `ServiceAccountAuthenticationUseCase.authenticateAs(userEmail)`, which returns an opaque `ServiceAccountAccess`. That value is passed into the use case or port, and the adapter turns it into delegated Google credentials. The admin OAuth session only unlocks the UI.

**Backup/sync flow.**
- `DriveBackupService` is the entry point. The admin passes a `BackupMode` explicitly: `FULL` always re-runs `InitialDriveSyncService` regardless of any saved cursor, and keeps the old `sync_state` row until that run's own commit replaces it; `INCREMENTAL` runs `DriveChangeSyncService` when a `sync_state` row exists and falls back to a full inventory when it doesn't.
- `StaleDrivePageTokenException` (from an `INCREMENTAL` run) also falls back to a full inventory, the same as an explicit `FULL` request.
- A `DriveScope` (a `key` — a user's email or a Shared Drive's `drive_id` — plus a `PERSONAL`/`SHARED_DRIVE` type) is threaded explicitly through every port and use case that routes on it (`DriveFileListingPort`, `DriveChangePort`, the three sync use cases, and their result records), replacing the old bare `scopeKey` string and its `contains("@")` inference. `GoogleDriveAdapter` branches on `scope.type()` to set `corpora`/`driveId` on the Google request, and every `StoredFile.ownerScope()` is stamped with `scope.key()`, never the impersonating user's email, so a Shared Drive's `owner_scope` stays stable regardless of who syncs it.
- `synchronizeSelectedDrives` backs up exactly the `AvailableDrive`s the admin checked in the UI (personal and/or specific Shared Drives) — it no longer enumerates drives itself, so `DriveBackupService` has no `DriveReadPort` dependency. For each selected drive it builds a `DriveScope` (`DriveScope.personal(access.impersonatedUserEmail())` or `DriveScope.sharedDrive(drive.id())`) and records shared ones through `DriveMetadataPort`/`drives` table. The whole run is one `BackupActivity.duringBackup` hold; if one scope throws, its `BackupResult.failed(...)` is recorded and the run continues with the remaining drives (a failed drive committed nothing and replays from its last cursor); `BackupSummaryText` renders the completion summary.
- Both sync services own their whole run and there is no on-disk capture store. They stream content from Drive (`DriveContentPort`, via `FileContentStreamingService`) straight into the ZIP being staged (`ArchiveSessionPort` → `LocalArchiveSessionAdapter`: temp file next to the target, manifest written last, atomic move on `publish`, discarded on `close` if never published). `InitialDriveSyncService` always re-downloads every live file into a Drive-shaped tree; `DriveChangeSyncService` streams the run's changed content as `content/<fileId>` after draining the change feed. `ArchiveRunPlanner` picks the archive's sequence number, base and path.
- **Parallel downloads.** Both sync services hand their per-file work to `ParallelContentFetcher` (`gdrive-backup.backup.download-concurrency` from `BackupProperties`, default 4, is only the starting value: `DownloadConcurrencyService` holds the live, session-only value the admin changes in Settings, refuses a change while a backup runs via `BackupActivity`, and the sync services read it through an `IntSupplier` when each run starts). `FileContentStreamingService.fetch` runs on worker threads and spools the bytes through `ArchiveSession.stage` (a `.spool-*.tmp` beside the staged ZIP); `write` runs only on the calling thread and appends the spool to the ZIP, picking stored or deflated per entry from its mime type (`isAlreadyCompressed`). `ParallelContentFetcher` hands results to that writer **as they complete, not in list order**: handing them over in order let one huge file at the head fill the `2 × concurrency` window with finished results behind it, so no new download started and only the big file kept downloading. Consequently ZIP entry order is not list order and varies between runs (the manifest keeps list order); entry names must not depend on write order, which is why a full run precomputes every entry name and passes them as `reservedEntryNames` so a PDF-fallback rename (`write(..., reservedEntryNames)`) never takes a name another file is about to use. `ArchiveSession.stage` must stay thread-safe and everything else on the session single-threaded. A concurrency of 1 never fetches ahead of the writer. In a full run `InitialDriveSyncService` reports progress when a download completes (on the worker thread, so `BackupProgressTracker` is synchronized). The same fetch wrapper also calls `downloadStarted`/`downloadFinished`/`downloadAborted` on the tracker, which puts a `FileDownload` (name plus full Drive path from `DrivePathResolver`) into `BackupProgress.downloads`; `OperationProgressPanel` (constructed with `listsDownloads = true` only for the Backup tab) shows them under the bar as two stacked sections, "Downloading (N)" and a scrollable "Downloaded (N)" of the last 20 finished (`OperationProgressText.downloadingRows`/`downloadedRows`; name only, path as tooltip); the tracker keeps those 20 and `BackupProgress.finishedDownloads` counts them all for the heading. Each row also shows the bytes received so far: `FileContentStreamingService.fetch(..., LongConsumer)` wraps the Drive stream in `ReportingInputStream` (throttled to ~200 ms, plus once at end of stream), the sync services forward it to `BackupProgressTracker.downloadProgressed`, and the total comes from `StoredFile.sizeBytes` (Drive's `size`, requested in `listAllFiles`/`listChanges`; `null` for Google-native files, not persisted). The panel updates rows in place by file id so tooltips survive the constant size changes. `GoogleDriveAdapter.download`/`export` retry through `DriveRetry`.
- **Commit protocol.** A run buffers its database effects (`files` metadata, `file_events`, `file_captures`, the `archives` row, the new `sync_state` cursor) in a `PendingCommit` and applies them in one SQLite transaction through `SyncCommitPort` only after the ZIP is published. A cancelled or failed run writes nothing and the next run replays from the last committed cursor; there is no per-page cursor checkpoint. A full run fetches its baseline page token before listing, and keeps the old cursor until its own commit replaces it.
- `DriveExportLimitException` triggers the PDF export fallback.
- **Merge.** `ArchiveMergeUseCase` (`ArchiveMergeService`) builds a `MERGED_FULL` from a drive's current chain using only archives: it resolves the chain from the `archives` rows, opens each archive through `ArchiveReaderPort` (`LocalArchiveReaderAdapter`), folds their manifests (`ChainFolder`), resolves the tree with `FlatTreePathResolver`, streams the bytes into an `ArchiveSession`, and commits the archive plus its `archive_sources` rows through `SyncCommitPort` without touching `sync_state`. It never reads Drive, `files` or `file_captures`. The manifest wire format lives in `ArchiveManifestJson`, shared by the session and reader adapters. A merge is an exclusive operation (`BackupActivity.duringExclusiveOperation`, which refuses to start beside a backup or another operation), reports progress as a one-drive job through `BackupProgressTracker`, and honors `BackupCancellation`. `ArchiveCatalogUseCase` (`ArchiveCatalogService`) lists each drive's archives as chain root, incremental, obsolete or earlier chain, with gap warnings; file existence, size and guarded deletion go through `ArchiveStoragePort`. `ArchiveDeletionUseCase` (`ArchiveDeletionService`) removes the archives a merge made obsolete: `prepare` deep-verifies the merged full and returns a `DeletionPlan` (including content that will be lost), `execute` refuses an unverified or stale plan, commits the database side through `ArchiveDeletionCommitPort` in one transaction, then deletes the files.

**Persistence.**
- `SqliteDatabase` opens a new JDBC connection for each operation.
- The backup root switches at runtime without rebuilding beans: `LocalBackupLocationAdapter.applyRoot` calls `LocalBackupRoot.switchTo(root)` and `SqliteDatabase.switchTo(root.resolve("backup.db"))` together, and every `Sqlite*Adapter` follows because they share the one `SqliteDatabase`. `BackupActivity` is a read-write lock: backups hold the read side through `DriveBackupService`, and a location change fails immediately if it can't take the write side.
- The schema is `src/main/resources/db/schema.sql`, applied at startup by an `ApplicationRunner` in `DatabaseConfiguration`.
- It only uses `CREATE TABLE/INDEX IF NOT EXISTS` and there is no migration tool, so changes to an existing table will not reach databases that already exist.

## Repository notes

- `build/` (gitignored Gradle leftovers) is not part of the Maven build; ignore it.
