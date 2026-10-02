# Implementation Plan — Google Workspace Drive Backup

This is the working roadmap for the app specified in
[gdrive-backup-app.md](gdrive-backup-app.md). The requirements document defines
expected behavior; this plan tracks what is built, what is next, and in what
order. Section references in **bold** (e.g. **Storage layout**, **Sync
algorithm**, **Known limitations**) point to sections of the requirements
document.

## Implementation progress

Status markers: `[x]` complete, `[-]` in progress, `[ ]` not started.

Last reviewed: 2026-10-01

### Completed

- [x] Spring Boot and JavaFX application startup, including Spring context loading.
- [x] Hexagonal architecture foundations and architecture boundary test.
- [x] Admin OAuth login with browser authorization approval.
- [x] Service-account authentication with domain-wide delegation and user impersonation.
- [x] Workspace user enumeration through the Admin SDK.
- [x] My Drive and Shared Drive listing through the impersonation-backed Drive adapter.
- [x] JavaFX user picker and Drive preview browsing, including folder navigation.
- [x] Focused unit tests and opt-in real-account integration tests for the implemented Google adapters and services.
- [x] Make the user-selection and impersonated Drive-preview flow discoverable and usable in the JavaFX layout.
- [x] Headless initial and incremental sync using `changes.list`. Initial listing persists metadata and downloads versions for non-folder files before saving the start token; incremental sync backs up changed revisions and links them to the current file version. Expired page tokens trigger a full re-inventory without duplicating unchanged versions. Both flows now report per-item progress through `BackupProgressTracker`.
- [x] Detection and persistence of renames, moves, trashing, deletion, and content revision events.
- [x] Versioned local storage writer with `owner/file/revision` paths and sanitized filesystem names. Superseded twice: first by the no-revision-retention decision (the `revision` path segment went away), then by the stream-to-archive decision, which removes the capture store altogether (see **Storage layout** and the **Stream content straight into archives** item under Not started).
- [x] Google-native export handling and the 10MB fallback behavior. Office exports fall back to PDF when the Google export limit is reported.
- [x] Per-drive backup scope selection: let the admin choose which drive(s) — the
  personal drive and/or one or more specific Shared Drives — to include in a
  backup job. The drive list gets a checkbox per row (`CheckBoxListCell`); the
  admin checks the drives to include and clicks **Sync selected drives**.
  Shared Drives synced this way are still deduplicated by `drive_id` via the
  `drives` table. This replaces the previous behavior of automatically
  including every Shared Drive the selected user could see.
- [x] Runtime location selection: let the admin choose and validate **one**
  backup root folder at runtime, applying the choice through
  configuration-backed ports rather than direct UI environment access. The
  backup history database (`backup.db`) always lives inside that root
  alongside the archive output — there is no
  separate database-location picker, so the database can never point
  somewhere other than the archives it describes (see **Backup root
  location**). The location is chosen only in the UI and lasts for the
  current session; every launch starts from `~/.gdrive-backup`. Changes are
  refused while a backup runs. Paths recorded in the database are relative to
  the root (`archives.archive_path` is stored relative to it), so relocating the whole root needs no database changes. This supersedes the earlier two-picker (destination + database)
  implementation.
- [x] Interruptible backups and recovery policy: the admin can cancel a
  running backup from the progress panel. For a single-drive job, Cancel
  stops immediately; for a multi-drive job, it asks the admin to choose
  stop immediately vs. finish the current drive then stop, via a
  `BackupCancellationUseCase` checked cooperatively between files/pages/
  drives (never mid-download, so an in-flight file always finishes). An
  interrupted result is never shown as complete — `BackupResult.cancelled`
  and a "Synchronization cancelled." status replace the completed message.
  Recovery is defined for what exists today (SQLite + the local versioned
  file store; archive packaging doesn't exist yet, so its temp-file/cleanup
  behavior is deferred to that P1 item): a stopped-early full inventory
  never establishes a `sync_state` baseline, so a later run — cancelled and
  retried or not — safely re-lists everything and only re-downloads what
  wasn't already recorded; incremental sync now checkpoints `sync_state`
  after every page of changes, not just the last one, which is also what
  fixes a latent crash-recovery bug where a mid-run crash would replay
  already-applied pages and duplicate `file_events` on retry.
- [x] Progress bar with elapsed and estimated-remaining time: shows current
  operation, elapsed time, and a guessed remaining time for the drive
  currently being synced, resetting as the job moves to the next one. For a
  multi-drive job, also shows which drive is current (by name), how many
  drives the job includes, how many have completed so far, and elapsed/
  estimated-remaining time for the whole job alongside the per-drive figures.
  A `BackupProgressTracker` domain service folds sync events into snapshots
  (indeterminate with a running count for incremental syncs, whose change
  total isn't known until the run ends); the JavaFX layer polls the latest
  snapshot on a timer.
- [x] Store and schema rework for the chain model: local storage under
  `LATEST_ONLY` no longer has a `revision` path segment (`backupRoot/<ownerScope>/<fileId>/<filename>`,
  replaced in place); `file_versions` is replaced by `file_captures` (adding
  a nullable `archive_id`); `file_events` gained a nullable `archive_id`; and
  an `archives` table (`sequence_number`, `base_archive_id`, `mode`,
  `revision_mode`, `cancelled`, cursor range) now exists for the upcoming
  archive writer to populate. `FileVersion`/`FileVersionPort`/`VersionStoragePort`
  were renamed to `FileCapture`/`FileCapturePort`/`CaptureStoragePort`
  throughout. No migration tool exists, so any existing local `backup.db` has
  to be deleted and recreated on next launch.
- [x] Scope type carried explicitly instead of inferred from `@`: a new
  `DriveScope` (`key` plus a `PERSONAL`/`SHARED_DRIVE` type) replaces the bare
  `scopeKey` string on every port and use case that routes on it —
  `DriveFileListingPort`, `DriveChangePort`, `InitialDriveSyncUseCase`,
  `DriveChangeSyncUseCase`, `DriveBackupUseCase` — and on the sync result
  records (`BackupResult`, `InitialSyncResult`, `SyncResult`).
  `GoogleDriveAdapter` now branches on `scope.type()` instead of
  `!scopeKey.contains("@")`, and the JavaFX summary label does the same
  instead of checking for `@` itself. `SyncStatePort`/`SyncState` stay keyed
  by the raw string, since persistence there doesn't branch on scope type.
- [x] SQLite schema and persistence for users, drives, files, captures, events, archives, and sync state. Schema initialization and all metadata/history repositories are in place, including `ArchivePort`/`SqliteArchiveAdapter`, and sync orchestration now writes to `archives` as part of each run.
- [x] Backup options and per-drive archive packaging: the admin picks full versus incremental mode in the UI before starting a sync; `INCREMENTAL` falls back to a full inventory when no baseline exists yet or the saved cursor has expired, and `FULL` always re-inventories regardless of any saved cursor. Each scope's sync run produces its ZIP archive (unless it was cancelled): a full run materializes the live, non-trashed files into a real Drive-shaped folder tree (`FlatTreePathResolver`, with multi-parent flattening, collision suffixing, and a depth/cycle-safe walk of the id-based parent chains); an incremental run writes an id-keyed delta of the run's captured content plus its events, or produces no archive at all when nothing changed. `LocalArchiveSessionAdapter` stages each ZIP to a temp file and atomically publishes it, embedding `manifest.json` (Gson, adapter-side only) before the run's database effects are committed, so a crash never leaves a DB row pointing at a missing file. Only `LATEST_ONLY` revision mode is implemented; history lives in the archive chain instead of a stack of local copies.

- [x] Stream content straight into archives (slice 1): there is no capture
  store any more. `FileContentStreamingService` streams Drive content into an
  `ArchiveSession` (`LocalArchiveSessionAdapter`: temp ZIP, manifest last,
  atomic publish, discard on close). A full run always re-downloads every live
  file into a Drive-shaped tree and fetches its baseline page token before
  listing; an incremental run drains the change feed in memory and then streams
  the changed content as `content/<fileId>`. Database effects are buffered in a
  `PendingCommit` and applied by `SqliteSyncCommitAdapter` in one transaction
  after the ZIP is published (cancelled or failed runs write nothing, and there
  is no per-page cursor checkpoint). `file_captures` is now an archive content
  index (`archive_id` and `entry_name`, no `local_path`) and `file_events.archive_id`
  is always set. Existing `backup.db` files must be deleted (no migration tool).

- [x] Self-describing manifests (streaming work, slice 2): every manifest is
  written in the documented `format_version` 1 shape (see **Manifest format**).
  A full manifest lists every listed file and folder; an incremental lists each
  file the run touched with its resulting name, parents, mime type, trashed
  state and, when its bytes are included, revision, entry, size and export
  format, plus `removed` records. Chain links use `base_sequence_number`. A file
  first seen in a run (for example a new folder) now produces a delta archive
  even with no event and no content, because an archives-only merge needs it.
  Manifests written before this slice are not readable.

- [x] Merge engine (streaming work, slice 3): `ArchiveMergeService` builds a
  `MERGED_FULL` from a drive's current chain using only its archives. It finds
  the chain by following `base_archive_id` from the latest archive, refuses on a
  missing link or unreadable archive (`ArchiveChainException`), checks every
  manifest against its database row and neighbour, folds the manifests (see
  **Manifest format**), resolves the tree with the same flat-tree rules as a
  from-scratch full, and streams the bytes from the archives that hold them,
  checking each size against its manifest. The merged full commits with its
  `archive_sources` rows and no `sync_state` change, and the next incremental
  chains onto it. An end-to-end test proves a merge extracts to the same tree as
  a from-scratch full of the same Drive, also with the file tables emptied.
  There is no UI, deletion, progress or cancellation yet (see Archive
  operations). Existing `backup.db` files must be recreated (new
  `archive_sources` table).

- [x] Archive operations foundations: `archives.scope_type` (an archive row can
  be turned back into a drive scope), `ArchiveStoragePort` (size and guarded
  deletion under `archives/`), and `ArchiveCatalogUseCase`, which lists each
  drive's archives as chain root, incremental, obsolete (consumed by a merge in
  the current chain, transitively) or earlier chain, with warnings for a broken
  base link or a missing file and `canMerge`/`hasObsolete` flags. A merge is an
  **exclusive operation** (`BackupActivity.duringExclusiveOperation`): it
  refuses to start beside a backup or another operation and backups refuse to
  start beside it, since progress and cancellation are shared. A merge reports
  progress as a one-drive job through the backup progress tracker, honors the
  cancel button, and returns `MergeResult` (cancelled writes nothing). Existing
  `backup.db` files must be recreated (new `scope_type` column).

- [x] Verified deletion of obsolete archives: `ArchiveDeletionUseCase`.
  `prepare` changes nothing: it re-reads the merged full completely (every entry
  streamed and its size compared with the manifest, so a corrupt ZIP is caught),
  checks the manifest against its record, and works out the index changes. It
  reports progress, honors cancel, and returns a plan listing the obsolete
  archives with sizes, how many capture rows will be re-pointed at the merged
  full or removed, how many events are kept, and the **content that will be lost**
  (a trashed or Drive-deleted file whose bytes exist only in an obsolete
  archive). `execute` refuses a plan with verification problems or one that no
  longer matches the chain, updates the database in one transaction first
  (re-point carried captures, remove the rest and clear a removed capture from
  `files.current_version_id` so an untrash re-captures the file, re-point events,
  drop the archives and their `archive_sources` rows), then removes the files
  newest first; a file that cannot be removed is reported, not fatal. Like a
  merge it is an exclusive operation. Tested end to end, including that the
  merged full, index and event history survive, backups continue, a later merge
  still equals a from-scratch full, and a corrupted merged full blocks it.

- [x] Archive manager UI: `ArchiveManagerPanel` (the first class in the new
  `adapter.in.javafx` package, kept out of `JavaFxApplication`) is shown after
  sign-in. A drive picker feeds a table of its archives (number, kind, created,
  size, state; obsolete and earlier-chain rows greyed, a missing file in red) and
  a warnings area, with **Merge into a full backup...** (confirmation, then the
  shared progress panel with its Cancel button), **Delete obsolete archives...**
  (progress while verifying, then a review dialog with the verification result,
  the exact archive files and sizes, the database changes and the content that
  will be lost; blocking when verification fails; **Delete** to confirm) and
  **Refresh**. A finished merge offers to continue straight into the deletion
  review. Buttons are disabled while an operation or a backup runs. The wording
  and formatting live in a JavaFX-free `ArchiveManagerText` with unit tests; the
  panel was checked by rendering it against canned data and by launching the app,
  and the dialogs have not been exercised against real archives yet. The unused
  `MERGED_INCREMENTAL` archive mode left over from the dropped snapshot
  operation was removed.

- [x] Tabbed authenticated screen (replaces the earlier three-column idea; see **Main window layout**): a common `SessionHeaderPanel` (selected Workspace user, status, sign-out) above four tabs — Backup, Archives (own `OperationProgressPanel`), History (`FileHistoryPanel`), Technical info (`TechnicalInfoPanel`, on-demand cards). Unit tests pass and the flows were checked by hand against a real account.
- [x] Windows packaging and clean-machine verification: credentials persist through
  `adapter.out.credentialstorage.WindowsCredentialManagerAdapter` (Windows Credential
  Manager, chunked with a manifest since a service-account key exceeds a single
  entry's size limit; the in-memory adapter stays wired everywhere else, including
  Linux CI). Ships as a self-contained `jpackage` app-image (native `.exe` launcher
  plus a bundled, trimmed JRE) rather than an MSI/EXE installer, since either would
  need the WiX Toolset or Inno Setup installed as an extra dependency neither this
  environment nor GitHub's Windows runners are guaranteed to have; distributed as a
  zipped folder instead. A `windows-package` Maven profile (Windows-only activation)
  builds it, and a non-blocking `windows-latest` CI job builds and uploads it as an
  artifact on every push. Pushing a `v*` tag runs `.github/workflows/release.yml`,
  which gates on `./mvnw verify`, builds the app-image with `-Dapp.version` taken
  from the tag, and publishes it as `gdrive-backup-<version>-windows-x64.zip` on a
  GitHub Release with generated notes. Verified end to end on a second, clean Windows machine with
  no JDK installed: Settings → import credentials → sign in → run a backup all worked
  from the app-image build.

- [x] Header identity polish: the header shows the app version (from Spring
  Boot's `BuildProperties`, via a new `ApplicationInfo` domain model and
  `ApplicationInfoConfiguration`, with `app.version` preferred over the
  Maven `project.version`/`-SNAPSHOT`), the admin's email-domain favicon
  (Google's public favicon service — there is no API for a Workspace org's
  own custom logo), and the signed-in admin's avatar. The avatar comes from
  Drive `about.get` `user(displayName,photoLink)` via the service account
  impersonating the admin (`DriveUserProfilePort`/`GoogleDriveUserProfileAdapter`,
  mirroring the existing `DriveUsageQuotaPort` slice) — no new OAuth scope —
  shown as initials right after sign-in and replaced by the photo once it
  loads, or left as initials if there is none or it fails to load. The
  version also appears on the login screen and in a new static Application
  card on the Technical info tab. See **Main window layout** and
  **Technical info tab**.

### In progress

- [-] Continue exposing the remaining backend capabilities through the UI.
- [-] Backup trigger, progress reporting, and partial-failure handling. The UI selects initial or incremental synchronization for the selected user and shows a live progress bar with current-operation status and elapsed/estimated-remaining time (per drive and, for a multi-drive job, for the whole job), then reports the number of inventoried files or processed changes per selected drive on completion. The admin can cancel a running job (see the interruptible-backups item above). If one selected drive fails, the run records the failure and continues with the remaining drives, and the completion summary names each failed drive with its reason and any drive never started after a cancel (each drive commits independently, so a failed one replays from its last cursor). An org-wide sweep across every Workspace user remains.

### Not started

None currently.

Update the status markers and the `Last reviewed` date as each vertical slice
is completed; keep [gdrive-backup-app.md](gdrive-backup-app.md) as the source
of truth for expected behavior.

### Approved prioritization

**P0 — required for a usable and safe v1**

- [x] Runtime backup root location selection (database and archives share one root).
- [x] Full versus incremental backup selection.
- [x] Per-drive backup scope selection (personal drive and/or specific Shared
  Drives), replacing automatic inclusion of every visible Shared Drive.
- [x] Interruptible backups with defined database and archive recovery behavior
  (a cancelled or failed run writes no archive and commits nothing; see the
  P1 archive items).
- [x] Progress bar with concise current-operation status, elapsed time, and
  estimated remaining time.

**P1 — complete the backup product**

- [x] Per-drive archive output, each with its own manifest: a flat, directly
  uploadable tree for a full run, an id-keyed delta for an incremental one,
  none when an incremental run finds no changes. Each archive is a ZIP file
  with its manifest embedded at the root; a cancelled run writes no archive
  and no chain entry. `sequence_number` counts across the drive's whole
  archive folder, not per chain: chains are found by their base links, so a
  from-scratch full simply starts a new chain, which the Archive manager shows
  as the current one and lists the older one as an earlier chain.
- [x] Stream content straight into archives, with no capture store: a full
  archive is built from scratch by streaming every file from Drive, or from a
  complete set of incremental archives (base full plus every delta) through the
  merge below. See **Storage layout**.
- [x] Archive operations: the **merge** operation — a `MERGED_FULL` built from the
  base full plus all current incrementals, which becomes the chain's new root
  so incremental backups continue after it — with an option to delete the
  superseded partial archives. Manual, per drive, reads only archives, and
  refuses to run on a chain with a missing link. Done: merge engine, catalog,
  verified deletion and the Archive manager UI (see the Completed list).
- [x] Chain-gap detection and warnings, since deleting an archive now permanently
  destroys the history it held. `ArchiveCatalogService` reports gap warnings,
  shown in the Archive manager.
- [-] Partial-failure handling and a completion summary: done for the drives
  selected for one user (a failing drive no longer stops the others); the
  organization-wide sweep across every Workspace user is still to do.

**P2 — operational improvements**

- [x] History view: search backed-up files by name and see one file's renames,
  moves, trashing and captured revisions in time order, with the archive that
  recorded each (`FileHistoryUseCase`, `FileHistoryPanel`, in the History tab).

- [x] Download throughput: both sync services download several files at once
  (`gdrive-backup.backup.download-concurrency`, default 4) through
  `ParallelContentFetcher`. Downloads are spooled next to the staged ZIP and
  written by a single thread in list order, so archives, entry names, progress
  and the commit protocol are unchanged. Already-compressed content is stored
  rather than deflated, and Drive content calls retry with backoff (`DriveRetry`).
  The admin can change the concurrency for the session in the Settings dialog
  (`DownloadConcurrencyUseCase`); it is not persisted across launches. The
  Backup tab's progress panel shows two lists under the bar, "Downloading" and
  "Downloaded" (the last 20, with the total in the heading), with each file's
  name, full Drive path as a tooltip, for both full and incremental runs, and
  the live downloaded size beside each
  name ("12.4 MB of 80.0 MB" when Drive reports a size, the running amount for
  Google-native exports).
  See [download-throughput-plan.md](download-throughput-plan.md) and **Sync
  algorithm**, step 7.

**P3 — delivery and UX refinements**

- [x] Tabbed authenticated-screen redesign (see **Main window layout**).
- [x] Backup tab guided-steps refinement: the flat list/combo layout became
  three numbered steps (Where/What/How), each drive row now shows its last
  archive or chain problem idle and live per-drive status during a run
  (`BackupDrivePanel`, `BackupDriveText`, `BackupModePicker`), and the Drive
  contents preview is a collapsible panel loaded on demand instead of a
  click-per-row list.
- [x] Windows packaging and clean-machine verification (shipped as a self-contained
  app-image, not an installer — see the Completed list).
- [x] Header identity polish: app version, domain favicon, admin avatar (see
  the Completed list).

Implementation sequence: runtime location selection; backup-job options and
state; drive scope selection; progress/cancellation/recovery; per-drive archive
packaging; partial-failure summary and history; UI redesign and
Windows packaging; header identity polish.

---

## Build order (original suggestion)

1. Headless sync core: service-account auth, impersonation, `changes.list`
   loop, SQLite schema, incremental diff logic (rename/move/trash/delete
   detection). Prove this from the command line before touching UI.
2. Google-native export handling + 10MB fallback.
3. Versioned local storage writer.
4. Admin SDK user enumeration.
5. JavaFX shell: login gate, user picker, Drive/Shared-Drive browser (reusing
   step 1's fetch code).
6. Backup options (full/incremental), progress UI,
   archive packaging, and history view.
7. Low-priority JavaFX layout analysis and three-panel redesign.
8. `jpackage` → self-contained Windows app-image; test on a clean machine without a
   preinstalled JDK.

---

