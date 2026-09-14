# Google Workspace Drive Backup — Windows Desktop App

## Project overview

A Windows desktop application that backs up **Google Drive data (My Drive + Shared
Drives) for every user in a non-profit Google Workspace organization**, with
versioned local history and an admin-facing UI to preview and trigger backups.

The organization runs this periodically against **external hard drives**, not a
paid cloud-backup service — a non-profit budget constraint, not an arbitrary
choice, and one with real consequences (a drive may be unplugged, swapped, or
absent between runs; see Known limitations). **Restoring from a backup is out
of scope for this phase**; it's planned as a later initiative once backup is
solid.

Target stack: **Java + Spring Boot** (backend/service layer), **JavaFX** (UI),
**SQLite** (local state/history), packaged as a native Windows installer via
`jpackage`.

## Implementation progress

Status markers: `[x]` complete, `[-]` in progress, `[ ]` not started.

Last reviewed: 2026-09-13

### Completed

- [x] Spring Boot and JavaFX application startup, including Spring context loading.
- [x] Hexagonal architecture foundations and architecture boundary test.
- [x] Admin OAuth login with browser authorization approval.
- [x] Service-account authentication with domain-wide delegation and user impersonation.
- [x] Workspace user enumeration through the Admin SDK.
- [x] My Drive and Shared Drive listing through the impersonation-backed Drive adapter.
- [x] JavaFX user picker and Drive preview browsing, including folder navigation.
- [x] Focused unit tests and opt-in real-account integration tests for the implemented Google adapters and services.

### In progress

- [x] Make the user-selection and impersonated Drive-preview flow discoverable and usable in the JavaFX layout.
- [-] Continue exposing the remaining backend capabilities through the UI.
- [-] SQLite schema and persistence for users, drives, files, versions, events, and sync state. Schema initialization and all metadata/history repositories are in place; sync orchestration remains.

### Not started

- [x] Headless initial and incremental sync using `changes.list`. Initial listing persists metadata and downloads versions for non-folder files before saving the start token; incremental sync backs up changed revisions and links them to the current file version. Expired page tokens trigger a full re-inventory without duplicating unchanged versions. Both flows now report per-item progress through `BackupProgressTracker`.
- [x] Detection and persistence of renames, moves, trashing, deletion, and content revision events.
- [x] Versioned local storage writer with `owner/file/revision` paths and sanitized filesystem names.
- [x] Google-native export handling and the 10MB fallback behavior. Office exports fall back to PDF when the Google export limit is reported.
- [-] Backup trigger, progress reporting, and partial-failure handling. The UI selects initial or incremental synchronization for the selected user and shows a live progress bar with current-operation status and elapsed/estimated-remaining time (per drive and, for a multi-drive job, for the whole job), then reports the number of inventoried files or processed changes per selected drive on completion. The admin can cancel a running job (see the interruptible-backups item below). If any one scope fails the whole run stops. An org-wide sweep across every Workspace user and partial-failure handling with a completion summary remain.
- [x] Per-drive backup scope selection: let the admin choose which drive(s) — the
  personal drive and/or one or more specific Shared Drives — to include in a
  backup job. The drive list gets a checkbox per row (`CheckBoxListCell`); the
  admin checks the drives to include and clicks **Sync selected drives**.
  Shared Drives synced this way are still deduplicated by `drive_id` via the
  `drives` table. This replaces the previous behavior of automatically
  including every Shared Drive the selected user could see.
- [-] Backup options and archive packaging: let the admin choose full versus incremental mode and all versus latest revisions, then produce one self-contained archive per selected drive.
  Full versus incremental mode selection is implemented: the admin picks the mode in the
  UI before starting a sync, `INCREMENTAL` falls back to a full inventory when no
  baseline exists yet or the saved cursor has expired, and `FULL` always re-inventories
  regardless of any saved cursor. All-versus-latest-revision selection and
  per-drive archive packaging remain.
- [x] Runtime location selection: let the admin choose and validate the backup
  destination and SQLite database location, applying the choices through
  configuration-backed ports rather than direct UI environment access.
  Locations are chosen only in the UI and last for the current session; every
  launch starts from `~/.gdrive-backup/backupRoot` and
  `~/.gdrive-backup/backup.db`. Changes are refused while a backup runs.
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
- [ ] History view for file events and versions.
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

- One self-contained archive output per selected drive, each with its own manifest.
- All-revisions versus latest-only selection. Historical revision retrieval
  needs separate Google API/design validation.
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
- **Backup location**: the admin can choose the local destination directory at
  runtime. The selected location is used for backup staging/archive output and
  is displayed before a backup starts.
- **Database location**: the admin can choose the SQLite database file location
  at runtime. The application must validate that the location is writable and
  make clear when a location change selects a different backup history.
- **Versioning**: the admin chooses whether a backup keeps every available file
  revision or only the latest revision. The selection applies to the backup job
  and must be visible before it starts.
- **Archive output**: each completed backup job must deliver one
  self-contained archive **per selected drive** — a personal drive and each
  Shared Drive get their own archive and manifest, never a single archive
  combining several drives. The archive format and manifest layout need a
  design decision; ZIP is the initial candidate.
- **Backup mode**: the admin chooses a full backup or an incremental backup.
  A full backup inventories and archives the selected scope regardless of its
  change cursor; an incremental backup uses the saved `changes.list` cursor.
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
- **Restore**: explicitly out of scope for this phase. A later initiative once
  backup itself is solid.

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
  head_revision_id
  current_version_id       -- FK -> file_versions

file_versions
  id (PK)
  file_id (FK)
  revision_id
  timestamp
  local_path
  size_bytes

file_events                -- rename / move / trash / delete / content
  id (PK)
  file_id (FK)
  event_type               -- 'rename' | 'move' | 'trash' | 'untrash' | 'delete' | 'content'
  old_value
  new_value
  timestamp

sync_state
  scope_key (PK)            -- user email or drive_id
  page_token                -- changes.list startPageToken
```

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
   - `file.trashed` flips `false → true` → `file_events`: `trash` (keep
     version history; don't hard-delete data).
   - `file.trashed` flips `true → false` → `file_events`: `untrash`.
   - `name` differs from stored → `file_events`: `rename`.
   - `parents` (or `drive_id`, if moved across Shared Drives) differs →
     `file_events`: `move`.
   - `headRevisionId` differs → download/export content, insert new
     `file_versions` row, update `current_version_id`.
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

```
backupRoot/
  users/<userEmail>/<fileId>/<revisionId-or-timestamp>/<filename>
  shared-drives/<driveId>/<fileId>/<revisionId-or-timestamp>/<filename>
```

- One subfolder per file *version*, not one full-tree snapshot per run — only
  changed files get a new version folder; unchanged files just get their
  "last seen" timestamp bumped in SQLite.
- No retention policy required for v1, but the schema (`file_versions`)
  supports adding "keep last N versions" or "keep for N days" later without a
  redesign.

The directory layout above is the current internal staging/history layout. It
must be revised so the user-facing result of each backup is one self-contained
archive **per selected drive**, each with its own manifest that identifies its
scope (personal drive or a specific Shared Drive), backup mode, revision mode,
and captured files.

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
  personal drive and/or specific Shared Drives), the backup and revision
  modes, then run full/incremental sync, show per-user progress, package one
  self-contained archive per selected drive, and surface partial failures
  (suspended accounts, revoked access, etc. are expected at org scale).
- **History view**: query `file_events` + `file_versions` for a selected file
  to show renames/moves/trashes/versions over time.
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
6. Backup options (full/incremental and all/latest revisions), progress UI,
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
