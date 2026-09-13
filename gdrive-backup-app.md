# Google Workspace Drive Backup — Windows Desktop App

## Project overview

A Windows desktop application that backs up **Google Drive data (My Drive + Shared
Drives) for every user in a non-profit Google Workspace organization**, with
versioned local history and an admin-facing UI to preview and trigger backups.

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

- [-] Headless initial and incremental sync using `changes.list`. Initial listing persists metadata and downloads versions for non-folder files before saving the start token; incremental sync backs up changed revisions and links them to the current file version. Expired page tokens now trigger a full re-inventory without duplicating unchanged versions; richer progress reporting remains.
- [x] Detection and persistence of renames, moves, trashing, deletion, and content revision events.
- [x] Versioned local storage writer with `owner/file/revision` paths and sanitized filesystem names.
- [x] Google-native export handling and the 10MB fallback behavior. Office exports fall back to PDF when the Google export limit is reported.
- [-] Backup trigger, progress reporting, and partial-failure handling. The UI now selects initial or incremental synchronization for the selected user and reports the number of inventoried files or processed changes; full progress and partial-failure reporting remain.
- [ ] History view for file events and versions.
- [ ] Scheduled unattended backups.
- [ ] Windows packaging with `jpackage` and clean-machine verification.

This section is the working roadmap. Update the status markers and the
`Last reviewed` date as each vertical slice is completed; keep the detailed
requirements below as the source of truth for expected behavior.

---

## Core requirements (confirmed)

- **Scope**: back up Drive data for *every user in the organization*, not just one
  account — requires admin-level access.
- **Google-native files** (Docs/Sheets/Slides): exported to Office formats
  (`.docx` / `.xlsx` / `.pptx`), not kept in native Google format.
- **Storage**: local disk only (no NAS/cloud target for v1).
- **Versioning**: keep full dated version history per file, not just the latest
  snapshot.
- **Admin UI**: lets the admin log in via OAuth (as an access gate) and browse
  both personal (My Drive) and Shared Drives, per org user, as a **preview**
  before running a backup. The UI does not need per-user self-service access —
  admin-only.
- Should also track **renames, moves, trashing, and deletion** of files over
  time, not just content changes.

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
- **Backup trigger**: run full/incremental sync, show per-user progress,
  surface partial failures (suspended accounts, revoked access, etc. are
  expected at org scale).
- **History view**: query `file_events` + `file_versions` for a selected file
  to show renames/moves/trashes/versions over time.

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
6. Backup trigger + progress UI + history view.
7. `@Scheduled` job for unattended runs.
8. `jpackage` → Windows installer; test on a clean machine without a
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
