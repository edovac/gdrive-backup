# Google Workspace Drive Backup — Windows Desktop App

## Project overview

A Windows desktop application that backs up **Google Drive data (My Drive + Shared
Drives) for every user in a non-profit Google Workspace organization**, with a
full-plus-incremental archive history and an admin-facing UI to preview and
trigger backups.

The organization runs this periodically against **external hard drives**, not a
paid cloud-backup service — a non-profit budget constraint, not an arbitrary
choice, and one with real consequences (a drive may be unplugged, swapped, or
absent between runs; see Known limitations).

**Programmatic restore is out of scope for this phase** and is planned as a
later initiative. The supported recovery path is manual: a full archive is a
Drive-shaped folder tree the admin can upload directly to a new Drive, trading
away file history for a usable copy (see **Archive output**).

Target stack: **Java + Spring Boot** (backend/service layer), **JavaFX** (UI),
**SQLite** (local state/history), packaged as a native Windows installer via
`jpackage`.

## Implementation progress

Status markers: `[x]` complete, `[-]` in progress, `[ ]` not started.

Last reviewed: 2026-09-15

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
- [x] Versioned local storage writer with `owner/file/revision` paths and sanitized filesystem names. Superseded by the no-revision-retention decision: the `revision` path segment and the accumulation of copies are being removed (see **Local storage layout**).
- [x] Google-native export handling and the 10MB fallback behavior. Office exports fall back to PDF when the Google export limit is reported.
- [x] Per-drive backup scope selection: let the admin choose which drive(s) — the
  personal drive and/or one or more specific Shared Drives — to include in a
  backup job. The drive list gets a checkbox per row (`CheckBoxListCell`); the
  admin checks the drives to include and clicks **Sync selected drives**.
  Shared Drives synced this way are still deduplicated by `drive_id` via the
  `drives` table. This replaces the previous behavior of automatically
  including every Shared Drive the selected user could see.
- [x] Runtime location selection (superseded — see **Not started**): let the
  admin choose and validate the backup destination and SQLite database
  location *independently*, applying the choices through configuration-backed
  ports rather than direct UI environment access. Locations are chosen only in
  the UI and last for the current session; every launch starts from
  `~/.gdrive-backup/backupRoot` and `~/.gdrive-backup/backup.db`. Changes are
  refused while a backup runs. This conflicts with the now-decided **Backup
  root location** requirement (single root for the database and the
  archives): the two-picker implementation still works but needs to be
  collapsed into one root picker before archive packaging ships, so the
  database can never point somewhere other than the archives it describes.
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

### In progress

- [-] Continue exposing the remaining backend capabilities through the UI.
- [-] SQLite schema and persistence for users, drives, files, captures, events, archives, and sync state. Schema initialization and all metadata/history repositories are in place; sync orchestration remains.
- [-] Backup trigger, progress reporting, and partial-failure handling. The UI selects initial or incremental synchronization for the selected user and shows a live progress bar with current-operation status and elapsed/estimated-remaining time (per drive and, for a multi-drive job, for the whole job), then reports the number of inventoried files or processed changes per selected drive on completion. The admin can cancel a running job (see the interruptible-backups item above). If any one scope fails the whole run stops. An org-wide sweep across every Workspace user and partial-failure handling with a completion summary remain.
- [-] Backup options and archive packaging: let the admin choose full versus incremental mode, then produce the per-drive archive output described under **Archive output** (a flat, uploadable tree for a full run, an id-keyed delta for an incremental one, none when an incremental run finds no changes). Only `LATEST_ONLY` revision mode is implemented; history lives in the archive chain instead of a stack of local copies.
  Full versus incremental mode selection is implemented: the admin picks the mode in the
  UI before starting a sync, `INCREMENTAL` falls back to a full inventory when no
  baseline exists yet or the saved cursor has expired, and `FULL` always re-inventories
  regardless of any saved cursor. Per-drive archive packaging and the archive
  operations remain.

### Not started

- [ ] Collapse the backup-destination and database-location pickers into one
  `backupRoot` picker (see **Backup root location**): remove the independent
  database-location UI/port, derive `backup.db`'s path from the chosen root
  instead of letting it be set separately, and switch `file_captures.local_path`
  (and the upcoming `archives.archive_path`) from absolute paths to paths
  stored relative to `backupRoot`, resolved against `backupRoot` at read time.
  No migration tool exists, so this also means any existing local `backup.db`
  has to be recreated.
- [ ] Archive operations: squash consecutive deltas into a merged delta,
  collapse a chain into a flat uploadable tree, warn on chain gaps, and start a
  new chain when a full backup runs on a scope that already has one.
- [ ] History view for file events and captures.
- [ ] Scheduled unattended backups.
- [ ] Windows packaging with `jpackage` and clean-machine verification.
- [ ] Low-priority authenticated-screen UX analysis and a three-column layout for user, Drive, and quota/report information.

This section is the working roadmap. Update the status markers and the
`Last reviewed` date as each vertical slice is completed; keep the detailed
requirements below as the source of truth for expected behavior.

### Approved prioritization

**P0 — required for a usable and safe v1**

- [x] Runtime backup-destination and database-location selection.
- [x] Full versus incremental backup selection.
- [x] Per-drive backup scope selection (personal drive and/or specific Shared
  Drives), replacing automatic inclusion of every visible Shared Drive.
- [x] Interruptible backups with defined database and archive recovery behavior
  (archive recovery deferred to the P1 archive-packaging item, since no
  archive writer exists yet).
- [x] Progress bar with concise current-operation status, elapsed time, and
  estimated remaining time.

**P1 — complete the backup product**

- Per-drive archive output, each with its own manifest: a flat, directly
  uploadable tree for a full run, an id-keyed delta for an incremental one,
  none when an incremental run finds no changes. Archives are organized into
  per-scope chains rooted in one full backup; starting a new full backup on a
  scope with an existing chain begins a new chain, leaving the old one intact.
  The chain is tracked in SQLite and mirrored in each manifest, with a
  `sequence_number` used for archive naming. Each archive is a ZIP file with
  its manifest embedded at the root; a cancelled run writes no archive and no
  chain entry (see **Known limitations**). Naming and directory layout are
  decided — see **Archive layout and naming** under **Local storage layout**.
- Archive operations: squash consecutive deltas into one `MERGED_INCREMENTAL`
  delta, and collapse a full archive plus its deltas into a single flat
  `MERGED_FULL` tree that becomes the chain's new base. Both refuse to run on a
  chain with a missing link, and both keep the superseded archives by default,
  offering the admin the option to delete them afterward.
- Chain-gap detection and warnings, since deleting an archive now permanently
  destroys the history it held.
- Partial-failure handling and a completion summary for organization-wide runs.

**P2 — operational improvements**

- History view.
- Scheduled unattended backups.

**P3 — delivery and UX refinements**

- Authenticated-screen three-column layout redesign.
- Windows installer and clean-machine verification.

Implementation sequence: runtime location selection; backup-job options and
state; drive scope selection; progress/cancellation/recovery; per-drive archive
packaging; partial-failure summary and history; scheduling; UI redesign and
Windows packaging.

---

## Core requirements (confirmed)

- **Scope**: back up Drive data for *every user in the organization*, not just one
  account — requires admin-level access.
- **Drive selection**: the admin chooses which drive(s) a backup job covers —
  the user's personal drive, one or more specific Shared Drives, or a
  combination — rather than a job always covering every drive automatically.
  The selection applies to the backup job and must be visible before it starts.
- **Google-native files** (Docs/Sheets/Slides): exported to Office formats
  (`.docx` / `.xlsx` / `.pptx`), not kept in native Google format.
- **Storage**: local disk only — specifically **external hard drives** connected
  to the admin's machine, not NAS or cloud, driven by the non-profit
  organization's budget (no NAS/cloud target for v1).
- **Backup root location**: the admin chooses **one** local destination folder
  at runtime — `backupRoot` — that is the root for everything the app writes:
  the SQLite database (`backupRoot/backup.db`), the archive output
  (`backupRoot/archives/...`), and the internal capture store
  (`backupRoot/<ownerScope>/...`). There is no separate database-location
  picker; the database always lives inside the chosen root, so the chain
  metadata and the archives it describes can never be pointed at different
  places by the UI. The application validates that the root is writable and
  makes clear when switching roots selects a different backup history.
  Keeping the root intact as a single unit — e.g. when copying or moving it to
  a different folder or drive — **is the administrator's responsibility**: the
  app does not detect or warn if the database and the archives it references
  are later pulled apart (for example, by moving only part of the tree), and
  paths recorded in the database are stored **relative to `backupRoot`**
  precisely so that moving the whole root elsewhere needs no database changes.
- **Revision mode is fixed per chain, and only one mode is built.** Every scope
  (a personal drive or a Shared Drive) has its own **backup chain**: an ordered
  sequence of archives rooted in one full backup, followed by zero or more
  incremental backups and any merged archives produced later. A chain's
  revision mode — `LATEST_ONLY` or a future `ALL_REVISIONS` — is chosen once,
  when the chain's full backup runs, and fixed for every archive added to that
  chain afterward. **`LATEST_ONLY` is the only mode implemented**: it never
  calls Drive's `revisions.list`, and the local store holds exactly one current
  copy per file, replaced when the content changes. There is no admin-facing
  revision-mode picker yet — `revision_mode` already exists on the `archives`
  row so `ALL_REVISIONS` can be added later without reshaping the schema or
  restarting chains, but building it needs separate Google API/design
  validation (see Known limitations). `head_revision_id` is the change
  detector either way, deciding whether a file's content needs re-downloading.
  Starting a **new** full backup on a scope that already has a chain begins a
  **new chain** — the old one is left in place, untouched, just no longer
  extended; this is also the only way a scope would ever move to a different
  revision mode. File history under `LATEST_ONLY` lives entirely in the
  **archive chain**, not in a stack of copies inside the local store.
- **Archive output**: each completed backup job delivers archive output **per
  selected drive** — a personal drive and each Shared Drive get their own
  archive and manifest, never a single archive combining several drives. What
  an archive contains depends on the backup mode:
  - A **full** run produces a complete, self-contained archive shaped like the
    original Drive: real folder hierarchy, real filenames, so the admin can
    upload it straight into a new Drive. The tree is materialized at export
    from the local store (which stays id-keyed) plus the `files` metadata,
    **not** from whatever that run happened to download: a full run re-lists
    everything but skips re-downloading files whose `headRevisionId` is
    unchanged, so packaging only what the run fetched would yield a nearly
    empty archive on an already-synced scope.
  - An **incremental** run produces a **delta** archive: an id-keyed payload of
    the content that changed, plus a manifest of that run's events (rename,
    move, trash, untrash, delete). Deltas are consumed by the merge operations
    rather than browsed, so they are deliberately not Drive-shaped — a partial
    tree could not be uploaded anyway, and a moved file's new path would lose
    where it came from. A rename with no new content still counts as a change
    and still produces a delta archive.
  - An incremental run whose changes feed returned nothing produces **no
    archive at all**, and the UI says so explicitly rather than presenting a
    completed backup whose archive is missing.

  Only a full archive is self-contained: a delta is usable only alongside the
  full archive it descends from and every delta in between, so the chain is
  tracked in SQLite (`archives`) as the source of truth and mirrored in each
  archive's own manifest, letting an archive be checked and trusted without the
  database. Each archive records its `sequence_number` within its chain, and
  archive filenames include it so related archives are identifiable at a
  glance. **Decided:** each archive is a single ZIP file (native Explorer
  support on Windows, easy to move to an external drive as one unit); its
  manifest is a JSON file embedded at the ZIP's root (e.g. `manifest.json`)
  rather than a sidecar file, so it can never be separated from the archive it
  describes. The full naming convention and directory layout are in
  **Archive layout and naming** under **Local storage layout**.
- **Flat-tree rules**: because a full archive is meant to be re-uploaded, it
  follows filesystem rules rather than Drive's:
  - **Live files only.** Trashed-but-undeleted files are recorded in the
    database and carried in delta archives, but never placed in the tree —
    re-uploading them would reinstate deleted content as if it were live.
  - **Real names preserved.** Only characters genuinely illegal on Windows
    (`\ / : * ? " < > |`) are replaced. This needs a gentler sanitizer than the
    internal store's, which reduces names to `[a-zA-Z0-9._@-]` and would mangle
    every accented filename.
  - **Collisions disambiguated.** Drive allows two files with the same name in
    one folder; a filesystem does not. Same-name siblings get a ` (2)`, ` (3)`
    suffix.
- **Archive operations**: the admin can reshape existing archives without
  re-contacting Google. The database is available during these operations, but
  the **archives are authoritative** — their contents and manifests drive the
  work, while the database locates the chain, confirms no link is missing, and
  records the result. This keeps merges correct even when the scope has been
  re-synced since those archives were written.
  - **Squash**: merge several consecutive deltas into one `MERGED_INCREMENTAL`
    archive covering the combined cursor range, still incremental. Events
    collapse to their net effect — a file renamed A→B then B→C appears once as
    A→C, and a file created then deleted inside the range drops out entirely.
  - **Collapse**: merge a full archive and the deltas that follow it into a
    single flat, directly-uploadable `MERGED_FULL` archive, applying each
    delta's events in order. This becomes the chain's new base, and replaces
    what would otherwise be a separate "convert versioned to non-versioned"
    step.
  - Both refuse to run on a chain with a missing link, and report which archive
    is absent.
  - Either operation keeps the archives it superseded by default; the admin is
    offered the option to delete them afterward once the merge result is
    confirmed good.
- **Backup mode**: the admin chooses a full backup or an incremental backup.
  A full backup inventories the selected scope regardless of its change cursor
  and archives it in full; an incremental backup uses the saved `changes.list`
  cursor and archives only that run's changes. See **Archive output** for what
  each mode's archive contains.
- **Progress feedback**: while a backup is running, the UI shows a progress bar
  and a concise status message describing the current operation (for example,
  enumerating files, downloading content, packaging the archive, or completing
  a scope), scoped to the **drive currently being synced** — the fraction shown
  reflects that drive's own progress, not a blended figure across every
  selected drive. Progress resets as the job moves to the next drive.
- **Multi-drive job status**: when a job covers more than one selected drive,
  the UI shows, distinct from the per-drive progress bar above:
  - **which drive is current**, identified by name (e.g. "Shared: Finance" or
    "My Drive"), not just a position in the list;
  - **how many drives this job includes** in total;
  - **how many of them have completed** so far.
  For example: "Shared: Finance — drive 2 of 3, 1 completed."
- **Elapsed and remaining time**: alongside the progress bar, the UI shows
  elapsed time and an estimated time remaining at **both levels**: for the
  drive currently being synced, and for the job as a whole (all selected
  drives combined). For a single-drive job the two coincide and only need
  showing once; for a multi-drive job both are shown together, e.g. "this
  drive: 1m elapsed, ~2m left — whole job: 4m elapsed, ~7m left." How each
  estimate is computed needs a design decision — the per-drive one might
  extrapolate from files or bytes processed so far against the totals known
  from enumeration; the job-level one additionally has to account for
  already-completed drives and however many remain (e.g. an average
  per-drive duration once at least one has finished). Both will necessarily
  be rough guesses, especially early in a drive's sync, right after
  switching from enumeration to download, or before any drive in the job
  has completed yet.
- **Interruptible backups**: the admin can cancel a running backup, visible
  and reachable from the same progress display.
  - For a single-drive job, cancelling stops that drive's sync; the
    interrupted result must never be presented as a completed backup.
  - For a job with **multiple selected drives**, cancelling asks the admin to
    choose between stopping immediately (abandoning the drive in progress
    too) or stopping after the current drive finishes (letting it complete
    normally, then not starting the next selected drive).
  - Either way, cancellation must leave the database and archive output in a
    defined, recoverable state.
- **Admin UI**: lets the admin log in via OAuth (as an access gate) and browse
  both personal (My Drive) and Shared Drives, per org user, as a **preview**
  before running a backup. The UI does not need per-user self-service access —
  admin-only.
- Should also track **renames, moves, trashing, and deletion** of files over
  time, not just content changes.
- **Restore**: programmatic restore is explicitly out of scope for this phase;
  re-uploading a flat full archive is the supported manual path under
  `LATEST_ONLY`. A later initiative once backup itself is solid — including how
  a future `ALL_REVISIONS` archive (full or incremental) would get turned back
  into usable files, which is undesigned for now.

---

## Auth model — two paths, one purpose each

### 1. Service account with domain-wide delegation (backend engine)

This is what actually performs the org-wide backup sweep.

- Created by a Workspace admin in Google Cloud Console; authorized for
  domain-wide delegation in the Admin Console.
- Required scopes:
  - `https://www.googleapis.com/auth/drive.readonly`
  - `https://www.googleapis.com/auth/admin.directory.user.readonly`
- For each org user, impersonate via
  `ServiceAccountCredentials.createDelegated(userEmail)` (from
  `google-auth-library-oauth2-http`) to act as that user and read their My
  Drive + the Shared Drives they can see.
- The service account JSON key is highly sensitive — restrict file
  permissions; consider encrypting at rest (e.g. Jasypt).

### 2. OAuth login (admin UI access gate + preview)

- Standard installed-app OAuth flow (loopback redirect), scope
  `drive.readonly`.
- Purpose is **narrow**: sign in to unlock the admin UI. It is *not* the data
  path for previewing other users' files.
- The actual "preview a user's Drive" feature reuses the **service account +
  impersonation** path (pick an org user → impersonate → browse) so there's
  only one Drive-fetching code path shared between backend sweep and UI
  preview.
- Store the admin's OAuth token in Windows Credential Manager (e.g. via
  `com.microsoft.credentialstorage` or a JNA wrapper), not a plain file.

---

## Libraries

| Purpose | Library |
|---|---|
| Drive API | `google-api-client`, `google-api-services-drive` (v3) |
| Admin SDK (user enumeration) | `google-api-services-admin-directory` |
| Auth / credentials | `google-auth-library-oauth2-http` (`ServiceAccountCredentials`, `UserCredentials`) |
| OAuth loopback flow | `google-oauth-client-jetty` or a manual local HTTP listener |
| Backoff/retry | `google-http-client`'s `ExponentialBackOff` |
| Local DB | `org.xerial:sqlite-jdbc`, optionally Spring Data JPA on top |
| Scheduling | Spring `@Scheduled`, or Quartz if cron-like flexibility is needed |
| UI | JavaFX (+ `javafx-weaver` for Spring DI into controllers) |
| Credential storage | `com.microsoft.credentialstorage` (Windows Credential Manager) or JNA |
| Logging | SLF4J + Logback |
| Packaging | `jpackage` (JDK 14+), optionally `jlink` for a trimmed runtime |

---

## Data model (SQLite)

```
users
  email (PK), display_name, last_synced_at

drives                      -- Shared Drives (deduped, not per-user)
  drive_id (PK), name, last_synced_at

files
  file_id (PK)
  owner_scope              -- user email OR drive_id
  name
  parents                  -- serialized parent id(s) / path
  drive_id                 -- null if in a user's My Drive
  mime_type
  trashed                  -- boolean
  head_revision_id         -- change detector only; no revision history is kept
  current_version_id       -- FK -> file_captures; the one copy held locally

file_captures              -- append-only log of content captures, one per download
  id (PK)
  file_id (FK)
  revision_id              -- the head revision this copy was taken from
  timestamp
  local_path               -- relative to backupRoot; meaningful only for the current
                           -- copy — superseded rows describe content that now lives
                           -- only in an archive
  size_bytes
  archive_id               -- FK -> archives; which archive carried this capture

file_events                -- rename / move / trash / delete / content
  id (PK)
  file_id (FK)
  event_type               -- 'rename' | 'move' | 'trash' | 'untrash' | 'delete' | 'content'
  old_value
  new_value
  timestamp
  archive_id                -- FK -> archives; which archive's run recorded this event

sync_state
  scope_key (PK)            -- user email or drive_id
  page_token                -- changes.list startPageToken

archives                   -- one row per archive written; the chain's source of truth
  id (PK)
  scope_key                -- user email or drive_id
  sequence_number          -- ordinal within the chain, for naming/display
  base_archive_id          -- FK -> archives; null for a chain's root full archive
  mode                     -- 'FULL' | 'INCREMENTAL' | 'MERGED_INCREMENTAL' | 'MERGED_FULL'
  revision_mode            -- 'LATEST_ONLY' | 'ALL_REVISIONS' (future); fixed for the whole chain
  created_at
  archive_path             -- relative to backupRoot; where the archive was written
  from_page_token          -- cursor range this delta covers; null for a full archive
  to_page_token
  cancelled                -- true if the run that produced this archive was interrupted
```

Each archive's manifest mirrors its `archives` row so the chain can be checked
and trusted from the archive alone. The database now lives inside the same
`backupRoot` as the archives (see **Backup root location**), and every stored
path is relative to that root rather than absolute, so relocating the whole
root to a new folder or drive needs no database changes; keeping the root
intact as a unit when doing so is the administrator's responsibility (see
Known limitations).

`file_captures` replaces the earlier `file_versions` table. It is a log of what
was captured and where it went, not a set of retained copies: under
`LATEST_ONLY`, only the row pointed at by `files.current_version_id` has
content in the local store; a future `ALL_REVISIONS` chain would retain more
than one capture per file. `archive_id` on both `file_captures` and
`file_events` is what lets the database locate which archive holds a file's
content or a given event without opening every archive on the drive — this is
a verification aid for the merge operations, not a substitute for reading the
archives themselves (see **Archive operations**: merges are archive-authoritative).

`owner_scope` and `scope_key` both hold either a user's email or a Shared
Drive's `drive_id`. Which of the two it is must be carried explicitly alongside
the key, not inferred from the value's shape — today the Drive adapter and the
storage path both rely on "an email contains `@`, a Drive ID doesn't".

---

## Sync algorithm (per user, per Shared Drive)

1. Load `sync_state.page_token` for this scope. If absent, do an initial full
   listing (`files.list`, `supportsAllDrives=true`,
   `includeItemsFromAllDrives=true`) to seed the `files` table, then call
   `changes.getStartPageToken` to establish a baseline.
2. On subsequent runs, call `changes.list` with the stored `page_token`,
   paging until exhausted, then store the returned `newStartPageToken`.
3. For each change entry, diff against the stored row for that `file_id`:
   - `removed: true` → mark deleted (`file_events`: `delete`). Note: this also
     fires on access revocation, not only true deletion — can't always
     distinguish the two.
   - `file.trashed` flips `false → true` → `file_events`: `trash` (keep the
     captured copy; don't hard-delete data).
   - `file.trashed` flips `true → false` → `file_events`: `untrash`.
   - `name` differs from stored → `file_events`: `rename`.
   - `parents` (or `drive_id`, if moved across Shared Drives) differs →
     `file_events`: `move`.
   - `headRevisionId` differs → download/export content, **replacing** the
     local copy, append a `file_captures` row, update `current_version_id`.
     The superseded copy is not retained locally; it survives only in whichever
     archive already carried it.
4. Handle a stale/expired `page_token` (e.g. after a long offline period) as
   an explicit error path that falls back to a full resync for that scope,
   rather than failing silently.
5. Shared Drives are synced **once per unique `drive_id`**, not once per user
   who can see them — avoid duplicate storage.

---

## Google-native file export

- Docs → `application/vnd.openxmlformats-officedocument.wordprocessingml.document`
- Sheets → `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`
- Slides → `application/vnd.openxmlformats-officedocument.presentationml.presentation`
- `files.export` has a **10MB cap per file** — plan a fallback (e.g. PDF
  export, or flag-and-skip with a logged warning) for files that exceed it.

---

## Local storage layout

Under `LATEST_ONLY` — the only revision mode built — the store is **id-keyed
and holds current state only**, one copy per file, replaced when the content
changes:

```
backupRoot/<ownerScope>/<fileId>/<filename>
```

- `ownerScope` is a user email or a Shared Drive's `drive_id`; which kind it is
  travels alongside the key rather than being inferred from the string.
- There is no revision segment: the store never holds two copies of a file.
  The `<revisionId>` level that used to sit here is gone under `LATEST_ONLY`.
  A future `ALL_REVISIONS` chain would need its own layout, still a
  placeholder (see Known limitations).
- Renames and moves are database updates, not file moves on disk. Nothing in
  the store's path depends on a file's name or its place in the Drive tree.
- Retention applies to **archives**, not to this store. The store is
  disposable: it can be rebuilt by a full sync, and what it cannot rebuild —
  history — lives in the archive chain.

The Drive-shaped folder tree is **materialized at export**, when a full archive
is packaged, by joining this store with the `files` metadata. Keeping the
risky part — rebuilding paths, resolving name collisions, dropping trashed
files — in one place that runs at export time means it can be verified there,
instead of every sync having to maintain a correct tree on disk.

The per-archive manifest identifies its scope (personal drive or a specific
Shared Drive), backup mode, captured files and events, and its position in the
archive chain.

### Archive layout and naming

Archives live in their own subtree, separate from the id-keyed store above,
so the human-facing deliverable the admin browses and uploads never mixes
with the disposable internal staging area. `backupRoot` is also where
`backup.db` lives (see **Backup root location**), so the whole tree — the
database, the archives, and the internal store — is one folder the admin can
move as a single unit:

```
backupRoot/backup.db
backupRoot/archives/<scopeFolder>/archive-<sequenceNumber>-<mode>.zip
```

- `<scopeFolder>` identifies the chain. Unlike the id-keyed store's sanitizer
  above (which reduces names to `[a-zA-Z0-9._@-]`), this uses the gentler
  **flat-tree** sanitizer (only characters illegal on Windows — `\ / : * ? "
  < > |` — are replaced), so folder names stay human-readable:
  - Personal drive: `My Drive (<email>)`, e.g. `My Drive (edoardo@example.com)`
    — the email is required, not optional, since an org-wide admin backs up
    more than one user's personal drive over time.
  - Shared Drive: `<drive name> (<drive_id>)`, e.g. `Finance (0AIJ4kZ...)` —
    the `drive_id` suffix keeps the folder unique and stable even if the
    Shared Drive is later renamed, or another Shared Drive shares its name.
- `<sequenceNumber>` is the archive's `sequence_number`, 4-digit zero-padded
  (`0001`, `0002`, ...) so filenames sort correctly in a plain file browser.
- `<mode>` is the archive's `mode` in kebab-case: `full`, `incremental`,
  `merged-full`, `merged-incremental`.
- Each ZIP embeds its manifest at the root as `manifest.json` (see **Archive
  output**), so the archive can never be separated from the record of what it
  contains.

Example, for a Shared Drive scope with a full backup, two incrementals, and a
squash of those two deltas:

```
backupRoot/archives/Finance (0AIJ4kZ...)/archive-0001-full.zip
backupRoot/archives/Finance (0AIJ4kZ...)/archive-0002-incremental.zip
backupRoot/archives/Finance (0AIJ4kZ...)/archive-0003-incremental.zip
backupRoot/archives/Finance (0AIJ4kZ...)/archive-0004-merged-incremental.zip
```

---

## UI (JavaFX)

- **Login screen**: "Sign in with Google" (OAuth, admin access gate only).
- **User picker**: list of org users (from Admin SDK enumeration, cached
  locally, refreshed periodically).
- **Drive browser**: `TreeView`/`TableView`, lazy-loaded on folder expand, two
  modes:
  - My Drive for the selected user (`q="'root' in parents"`)
  - Shared Drives the selected user belongs to (`drives.list` →
    `files.list(driveId=..., corpora="drive", includeItemsFromAllDrives=true,
    supportsAllDrives=true)`)
  - Both reuse the same impersonation-backed fetch code as the backend.
- **Backup trigger**: let the admin select which drive(s) to back up (the
  personal drive and/or specific Shared Drives) and the backup mode, then run
  full/incremental sync, show per-user progress, package the per-drive archive
  output for the chosen mode, and surface partial failures (suspended
  accounts, revoked access, etc. are expected at org scale).
- **Archive manager**: list each scope's archive chain in order, showing which
  archive is the full baseline and which deltas follow it, and warn when a link
  is missing. From here the admin runs the two archive operations — squash
  consecutive deltas, and collapse a chain into a flat uploadable tree — with a
  clear statement of what each one discards before it runs.
- **History view**: query `file_events` + `file_captures` for a selected file
  to show renames/moves/trashes and when its content was captured over time.
- **Layout follow-up (low priority)**: analyse and redesign the authenticated
  screen as three distinct columns/panels: user information and selection,
  Drive browsing/details, and quota/report details. Keep this separate from
  the backup-progress area so the primary task remains legible.

---

## Build order (suggested)

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
7. `@Scheduled` job for unattended runs.
8. Low-priority JavaFX layout analysis and three-panel redesign.
9. `jpackage` → Windows installer; test on a clean machine without a
   preinstalled JDK.

---

## Known limitations / open risks to keep in mind

- `removed: true` from `changes.list` conflates true deletion with the
  impersonated user losing access — can't fully distinguish without extra
  Admin SDK checks.
- Stale `page_token` after long downtime forces a full resync for that scope.
- `files.export` 10MB cap needs a defined fallback before it's hit in
  production.
- Domain-wide delegation setup is a manual, one-time Admin Console step and
  can't be automated from within the app — document it as a setup guide for
  the admin.
- Backup destinations are external hard drives, which can be unplugged,
  swapped, or simply absent when a run starts. The app doesn't yet detect
  whether the drive currently mounted at a saved path is the same physical
  drive used previously, or warn before writing to an unexpected one.
- A delta archive cannot restore a drive on its own: it needs the full archive
  it descends from plus every delta in between, in order. The collapse
  operation is the reassembly tooling; programmatic restore stays out of scope.
- **The archive chain is the only history.** The local store keeps one copy per
  file, so deleting or losing an archive permanently destroys the states it
  held — there is no local fallback. The app tracks the chain and warns about
  gaps, but cannot recover them.
- A flat full archive cannot represent Drive faithfully, and re-uploading one
  loses: files that shared a name in a folder (renamed with a ` (2)` suffix),
  any file that had multiple parents, and Google-native fidelity — an exported
  Doc returns as a `.docx`, not a Doc, unless converted on upload.
- **Decided**: the database and the archives no longer have independent
  locations — `backup.db` lives inside the same `backupRoot` as the archives
  it describes (see **Backup root location**), so they move together whenever
  the admin relocates that one folder. This is a structural guarantee, not an
  enforced one: nothing stops an admin from manually copying `backup.db` out
  on its own or deleting part of the tree, and the app does not detect that
  after the fact — each archive's embedded manifest remains the fallback for
  understanding it without the database, same as before.
- **Decided**: a cancelled run (full or incremental) writes no archive and no
  `archives` row — the run leaves the chain exactly as it was before it
  started. For an incremental run this matters most: a partial delta would be
  worse than none, since the next delta would resume *after* the changes the
  cancelled run dropped, silently losing them for good; `sync_state` is
  already checkpointed per-page (see **Sync algorithm**), so no Drive changes
  are lost even though no archive is produced for the cancelled portion. The
  archive writer stages output in a temp file/directory and only moves it into
  the chosen backup destination — and only inserts the `archives` row — after
  the run completes without cancellation; on cancellation the temp output is
  discarded. The `archives.cancelled` flag remains for a future case (e.g. a
  partial-full-archive option) but nothing sets it yet, since neither mode
  writes a cancelled archive today.
- Restore for a future `ALL_REVISIONS` archive (full or incremental) is
  undesigned, deferred along with restore generally.
- The internal layout for multiple revisions of one file inside an
  `ALL_REVISIONS` archive — how they're organized and named so a future
  restore can tell them apart — is a placeholder until that mode and restore
  are designed.
- The archive filename convention beyond "includes the chain's
  `sequence_number`" is deferred and needs a human-understandable naming
  scheme.
