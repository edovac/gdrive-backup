# Faster backup downloads

## Status

**Superseded in one respect (2026-10-02):** the plan below hands fetched content to the writer in
submission order. That let one very large file at the head of the window stall everything: the other
`2 × concurrency - 1` results finished and queued behind it, no new download was submitted, and only the big
file kept downloading. `ParallelContentFetcher` now hands results over as they complete, so a large file
occupies a single slot. ZIP entry order is therefore completion order, and a full run reserves every planned
entry name so a PDF-fallback rename cannot depend on write order.

Implemented 2026-10-01. Differences from the plan below:

- **Retries live in the adapter, not the HTTP transport.** The plan used
  `HttpBackOffUnsuccessfulResponseHandler`, but telling a rate-limit 403 from an
  export-size 403 needs the parsed error body, and reading it in a transport handler
  would consume the response the caller later parses to detect the export limit. So
  `DriveRetry` wraps the `download` and `export` calls and decides from the parsed
  `GoogleJsonResponseException` (429, 5xx, 403 with a rate-limit reason, and connection
  resets or timeouts before a response). The change feed and listing calls are not
  wrapped; they run one at a time and are not what parallelism stresses.
- **`ZipOutputStream` level is set once, to `BEST_SPEED`, at session open**, so it also
  applies to the merge path and the manifest, not only to staged entries.
- **A concurrency of 1 is the same code path** (a window of one), so it never fetches ahead of
  the writer; there is no separate sequential implementation.
- The `ArchiveSession` spool is `StagedContent` with size and CRC-32; `FetchedFile` carries
  the staged content, the export mime type and, after a PDF fallback, the extension the
  preferred export would have had.
- **Configurable from the UI (follow-up):** the concurrency is a session-only setting in the
  Settings dialog (`DownloadConcurrencyUseCase`, `DownloadConcurrencyService`), initialised from the
  property. The sync services take an `IntSupplier` and build their fetcher per run, so a change
  applies from the next run; changing it is refused while a backup is running. It is not saved
  across launches, matching the backup location.
- Tests: `ParallelContentFetcherTest`, `DriveRetryTest`, `BackupPropertiesTest`, plus new
  cases in `LocalArchiveSessionAdapterTest`, `FileContentStreamingServiceTest`,
  `InitialDriveSyncServiceTest` and `DriveChangeSyncServiceTest`. Not verified against a real
  Drive account: the wall-clock gain, and Drive's behaviour at concurrency above 4.

## Summary

**Answer: yes.** The main limit is that backups download one file at a time. Below are the
bottlenecks, followed by the fix.

Where the time goes:
- **One file at a time.** Each file waits out the full round trip, plus Google's conversion time for
  Docs, Sheets and Slides. On drives with many small files, this waiting, not connection speed,
  takes up most of the run.
- **Compression on the same thread.** Most Drive content (docx, pdf, jpg, mp4) is already compressed,
  so compressing it again costs CPU for almost no size gain.
- **No retries.** The requirements spec lists `ExponentialBackOff`, but nothing installs it. Retries
  become necessary once downloads run in parallel.

Proposed fix, in short:
1. Download 4 files at a time by default (configurable). Each download goes into a temporary file
   next to the ZIP being built.
2. A single thread writes the finished downloads into the ZIP in their original order, so archive
   order, file names and progress reporting stay predictable.
3. Stopping a backup or a failed download still throws away the partial ZIP and commits nothing.
4. Pick compression per file: skip compression (`STORED`) for content that's already compressed
   (docx/xlsx/pptx/pdf, jpg/png, mp4, zip), fastest DEFLATE for everything else. Existing archives
   stay readable.
5. Retry Drive calls with exponential backoff on 429 and 5xx errors. The "file too large to export"
   error is not retried, so the PDF fallback still triggers.

Expected gain: large for many small files and Google-format documents. Smaller for a few very large
files, because each one is still a single download.

## Context

Both sync services download **one file at a time** on a single thread:
`InitialDriveSyncService` (loop at line 72) and `DriveChangeSyncService` (loop at line 109) call
`FileContentStreamingService.stream(...)`, which opens a Drive stream and pipes it straight into the
single `ZipOutputStream` (`LocalArchiveSessionAdapter.Session.writeEntry`). Each file therefore pays the
full request latency (TLS, auth, Drive server-side export for Docs/Sheets/Slides, often 1–3 s) serially,
and DEFLATE compression runs inline on the same thread. For a drive with thousands of small files the
run is dominated by latency, not bandwidth.

Secondary issues:
- No retry/backoff on Drive calls (the spec's Libraries table lists `ExponentialBackOff`, but
  `GoogleDriveAdapter.drive()` never installs it). Required once requests run in parallel, since 429 /
  `userRateLimitExceeded` / 5xx become likely.
- Default DEFLATE level on content that is almost always already compressed (docx/xlsx/pptx/pdf/jpg/mp4)
  burns CPU on the writer thread for ~0 gain.

Goal: parallel downloads with bounded concurrency, a single serialized ZIP writer, retries with
backoff, and per-file compression — without changing archive format, commit protocol, or cancellation
semantics.

## Approach

### 1. Split "fetch" from "write" in the archive session (domain port)
`ZipOutputStream` can only take one writer, so workers must land bytes somewhere first.
Extend `domain/model/ArchiveSession.java`:
- `StagedContent stage(InputStream content) throws IOException` — **thread-safe**; copies the stream
  into a spool file next to the staging ZIP (same directory as `.archive-*.zip.tmp`, so cleanup and
  disk location follow the backup root). Returns an opaque handle (`StagedContent`, new domain record /
  interface: size + CRC-32 of the spooled bytes, computed while copying + `AutoCloseable` that deletes
  the spool). Computing the CRC during staging is what makes per-entry `STORED` possible in step below.
- `long writeEntry(String entryName, StagedContent staged, boolean compress) throws IOException` —
  writer-thread only; copies the spool into the ZIP and deletes it. When `compress` is false, sets
  `ZipEntry.setMethod(STORED)` with the size/CRC already on `StagedContent` (required by
  `ZipOutputStream` for stored entries — no trailing data descriptor is allowed for that method); when
  true, uses `DEFLATED` at `Deflater.BEST_SPEED`.
- Keep the existing `writeEntry(String, InputStream)` (used by merge; always `DEFLATED` — the merge path
  doesn't have pre-staged size/CRC and isn't the bottleneck this plan targets).
- `discard()`/`close()` also delete any spool files still outstanding (track them in a concurrent set).

Implement in `adapter/out/persistence/LocalArchiveSessionAdapter.java`. The reader uses `ZipFile`, which
handles `STORED` and `DEFLATED` entries transparently, so `LocalArchiveReaderAdapter` needs no change.

**Per-file compression choice** lives in `FileContentStreamingService` (or a small shared helper), keyed
off the mime type / export format decided at fetch time, not the raw Drive mime type:
- `STORED` (skip compression) for Drive's own export formats (docx/xlsx/pptx/pdf) and other common
  already-compressed binaries (jpg/png, mp4, zip).
- `DEFLATED` at `BEST_SPEED` for everything else (plain text, csv, json, uncompressed formats) and for
  `manifest.json`.

### 2. Split `FileContentStreamingService` into fetch + write
`domain/service/FileContentStreamingService.java`:
- `FetchedFile fetch(access, file, session)` — runs on a worker: picks export format, opens the Drive
  stream, handles the `DriveExportLimitException` → PDF fallback, and returns
  `(StagedContent, exportMimeTypeOrNull, usedPdfFallback)`. No entry name decided here.
- `StreamedFile write(file, fetched, session, entryName)` — runs on the writer thread: applies the
  PDF entry-name rewrite (`pdfEntryName`, which needs `session.containsEntry` and so must stay
  single-threaded), picks `STORED`/`DEFLATED` from the file's (possibly export) mime type, calls
  `session.writeEntry(name, staged, compress)`, builds the `FileCapture`.
- Keep `stream(...)` as `write(fetch(...))` so existing callers/tests still work.

### 3. Bounded parallel pipeline in both sync services
Add a small domain helper, e.g. `domain/service/ParallelContentFetcher` (plain Java, no Spring):
- Takes `downloadConcurrency` (int) and uses a virtual-thread executor
  (`Executors.newVirtualThreadPerTaskExecutor()`) gated by a `Semaphore(concurrency)`.
- Submits fetches in listing order while keeping at most `2 × concurrency` in flight (bounds spool disk
  usage), and the calling thread drains completed futures **in submission order**, calling
  `contentStreamingService.write(...)` and `progressTracker.itemProcessed(...)` — so
  `BackupProgressTracker` (not thread-safe) is still only touched from one thread and entry order and
  `FlatTreePathResolver` names stay deterministic.
- Cancellation: before each submit/drain check `cancellation.isImmediateStopRequested()`; on stop or on
  the first failure, cancel outstanding futures, shut the executor down, close fetched-but-unwritten
  `StagedContent`, and return/throw exactly as today (the session's `close()` discards the ZIP and
  any leftover spools, nothing is committed).

Wire it into:
- `InitialDriveSyncService.synchronize` — replace the per-file loop; ineligible files still just bump
  progress in order.
- `DriveChangeSyncService.synchronize` — replace the `contentFileIds` loop.

### 4. Retries with exponential backoff on Drive requests
`adapter/out/google/GoogleDriveAdapter.java` `drive(access)`: wrap the `HttpCredentialsAdapter` in an
initializer that also sets
- `HttpBackOffUnsuccessfulResponseHandler(new ExponentialBackOff())` with a
  `BackOffRequired` that retries 429, 5xx and 403 with reason `userRateLimitExceeded`/`rateLimitExceeded`,
- `HttpBackOffIOExceptionHandler` for transient I/O,
- sensible connect/read timeouts.
The export-limit detection (`isExportLimitExceeded`) must keep working: a 403 export-size error must
not be retried (check reason, not just status). `HTTP_TRANSPORT` is already shared; `NetHttpTransport`
is thread-safe.

### 5. Configuration
New `@ConfigurationProperties` record (per AGENTS.md), e.g.
`configuration/BackupProperties(int downloadConcurrency)` bound to `gdrive-backup.backup.download-concurrency`,
default **4** in `application.properties` (conservative vs Drive per-user quota; 6–8 is usually fine).
Inject into the two sync-service beans in `configuration/ServiceAccountConfiguration.java` (lines ~153–175).
No UI setting for now.

### 6. Docs
- `docs/gdrive-backup-app.md`: sync-algorithm section — downloads run with bounded concurrency into
  spool files, single writer, per-file compression (stored for already-compressed formats, fastest
  DEFLATE otherwise); retries/backoff now actually implemented.
- `docs/plan.md`: add/advance a "download throughput" item, bump `Last reviewed`.
- `CLAUDE.md` Backup/sync flow paragraph: mention the fetch/write split and spool files.

## Files to modify
- `src/main/java/org/nm/gdrive_backup/domain/model/ArchiveSession.java` (+ new `StagedContent`)
- `src/main/java/org/nm/gdrive_backup/adapter/out/persistence/LocalArchiveSessionAdapter.java`
- `src/main/java/org/nm/gdrive_backup/domain/service/FileContentStreamingService.java`
- new `src/main/java/org/nm/gdrive_backup/domain/service/ParallelContentFetcher.java`
- `src/main/java/org/nm/gdrive_backup/domain/service/InitialDriveSyncService.java`
- `src/main/java/org/nm/gdrive_backup/domain/service/DriveChangeSyncService.java`
- `src/main/java/org/nm/gdrive_backup/adapter/out/google/GoogleDriveAdapter.java`
- `src/main/java/org/nm/gdrive_backup/configuration/ServiceAccountConfiguration.java` + new properties record
- any in-memory/fake `ArchiveSession` used by tests

## Verification
- Unit tests:
  - `ParallelContentFetcherTest`: results written in submission order despite out-of-order completion;
    never more than N fetches concurrently (counting latch in a fake `DriveContentPort`); cancellation
    mid-run stops submissions and closes staged content; a failing fetch fails the run and discards.
  - `LocalArchiveSessionAdapterTest`: `stage` from several threads + `writeEntry(staged, compress)`
    produces a valid ZIP for both `STORED` and `DEFLATED` entries (correct size/CRC when stored); spool
    files gone after publish and after discard.
  - `FileContentStreamingServiceTest`: PDF fallback naming still correct via fetch/write; `STORED` chosen
    for docx/xlsx/pptx/pdf/jpg/mp4, `DEFLATED` for plain text/json/manifest.
  - Existing `InitialDriveSyncServiceTest` / `DriveChangeSyncServiceTest` pass unchanged in behaviour.
  - A `GoogleDriveAdapter` test for the retry predicate (429/5xx/rate-limit retried; export-limit 403 not).
- `./mvnw -Dtest=HexagonalArchitectureTest test`, then `./mvnw test`.
- Manual: `./mvnw spring-boot:run`, run a FULL backup of the same drive with concurrency 1 vs 4 and
  compare wall time; open the resulting ZIP and merge it to confirm archives are still readable.
