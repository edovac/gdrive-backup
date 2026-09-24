# Windows credential storage and packaging — remaining work

This documents the rest of the plan for removing `application.properties`/env-var
configuration and shipping a Windows installer. Stages 1–3 are done (see below);
stages 4–5 are Windows-only and need a real Windows machine to build and verify, so
they're written up here to resume in that environment. Once stage 5 lands, fold a
short summary back into `docs/gdrive-backup-app.md`'s P3 "Windows installer and
clean-machine verification" item and delete this file.

## Why

The app moved from a dev-only `.env`/env-var setup
(`GOOGLE_SERVICE_ACCOUNT_KEY`, `GOOGLE_OAUTH_CLIENT_SECRETS`, `GOOGLE_CLOUD_PROJECT_ID`,
all file paths) to a packaged, personal-use Windows installer. There's no shell to
export env vars from on a packaged install, and per `AGENTS.md`, credential files
can't be stored in plain files either way. Decisions already made (see git history on
`feature/windows` for the full discussion): secrets go into **Windows Credential
Manager**; a **Settings modal** replaces manual file/env setup; the installer is a
**self-contained EXE/MSI with a bundled JRE** via `jpackage`.

## Done (stages 1–3)

- **Domain**: `domain.model.CredentialConfiguration` / `CredentialValidation` /
  `CredentialValidationStatus`; outbound port `domain.port.out.CredentialStoragePort`;
  inbound port `domain.port.in.CredentialConfigurationUseCase`; domain service
  `domain.service.CredentialConfigurationService` (mirrors `BackupLocationService`,
  guards every mutation with `BackupActivity.changeLocations(...)`).
- **In-memory adapter**: `adapter.out.credentialstorage.InMemoryCredentialStorageAdapter`
  — the production bean on non-Windows OSes (and in CI) and the fake used by
  `CredentialConfigurationServiceTest`. Shares JSON-parsing validation with
  `adapter.out.credentialstorage.CredentialFileValidation` (uses
  `ServiceAccountCredentials.fromStream` / `GoogleClientSecrets.load` — the same
  Google libraries that build real credentials — so "valid" means "usable," not
  just "is JSON").
- **Startup wiring**: `GoogleServiceAccountAdapter`, `GoogleOAuthClientAdapter`,
  `GoogleCloudQuotaLimitAdapter` now take a `CredentialStoragePort` and re-derive
  credentials at point of use instead of caching a path-loaded value at construction.
  `ServiceAccountConfiguration`/`GoogleOAuthConfiguration` beans are unconditional
  (no more `@ConditionalOnExpression` on `google.service-account.key`).
  `ServiceAccountProperties`/`GoogleOAuthProperties` and the three
  `application.properties` lines are deleted. New
  `configuration.CredentialStorageConfiguration` wires the `CredentialStoragePort`
  and `CredentialConfigurationUseCase` beans — **currently hardcoded to
  `InMemoryCredentialStorageAdapter`**; this is exactly what stage 4 changes.
- **Settings UI**: `adapter.in.javafx.SettingsPanel` / `SettingsText`, a modal reachable
  from a "Settings" button on the login screen (`JavaFxApplication.buildLoginView`,
  via `showSettings()`) and from the signed-in header (`SessionHeaderPanel`'s new
  `onSettings` callback). Imports/clears the service-account key and OAuth client
  secrets via `FileChooser`, and sets the project id via a text field — all currently
  persisted only in memory for the running process.
- Full `./mvnw verify` passes (304 unit tests, `HexagonalArchitectureTest` updated
  with a `credential_storage_adapters_are_named_as_adapters` rule and an
  `allowEmptyShould(true)` on `spring_property_classes_have_conventional_names` since
  no `@ConfigurationProperties` classes remain). `GdriveBackupApplicationTest` boots
  clean with nothing configured against the in-memory adapter.

## Known risk to design around in stage 4

Windows generic Credential Manager entries cap out around **2560 bytes**
(`CRED_MAX_CREDENTIAL_BLOB_SIZE`) per blob. A service-account JSON key can be close
to that limit. The obvious library, `com.microsoft:credential-secure-storage`
(`StorageProvider.getCredentialStorage(...)` → `SecretStore<StoredCredential>`), has
**no built-in chunking** — on overflow it swallows the real Windows error and `add()`
just returns `false`. It was also **archived (read-only) in March 2025**; decision was
to use it anyway (small, MIT-licensed, vendorable later if it ever breaks) rather than
hand-roll a JNA wrapper from scratch.

## Stage 4 — real `WindowsCredentialManagerAdapter`

New `adapter.out.credentialstorage.WindowsCredentialManagerAdapter implements CredentialStoragePort`,
using `com.microsoft:credential-secure-storage`'s
`StorageProvider.getCredentialStorage(true, SecureOption.REQUIRED)` (persist=true;
REQUIRED because this adapter is only ever wired when the OS is already known to be
Windows — silently degrading to an insecure in-memory store here would violate
"never store credentials in plain files/insecure stores").

Reuse `CredentialFileValidation` for `validateServiceAccountKeyFile`/
`validateOAuthClientSecretsFile` — same as the in-memory adapter, no duplication needed.

**Chunking**, built from scratch (no library support):
- Target-name scheme: `gdrive-backup/service-account-key`,
  `gdrive-backup/oauth-client-secrets`, `gdrive-backup/project-id`, each split into
  `<base>/chunk-0`, `<base>/chunk-1`, … plus a `<base>/manifest` entry holding the
  chunk count as a decimal string.
- `CHUNK_CHAR_LIMIT = 1000` UTF-16 chars per chunk (→ 2000 bytes UTF-16LE per
  `StoredCredential`, safely under the 2560-byte cap).
- **Write ordering (crash-safety)**: write all numbered chunks first, write the
  manifest **last**. If `add()` returns `false` for any chunk, immediately delete any
  chunks already written in this import and throw an adapter-local unchecked
  exception (mirror `GoogleOAuthException`'s style) — a failed import must never leave
  a stale, half-written credential live under an old manifest.
- **Read**: read the manifest; absent → `Optional.empty()`. Present → read `count`
  chunks in order and concatenate; if any expected chunk is unexpectedly missing
  (corrupted state), treat the whole credential as absent (log it, don't throw) so the
  Settings panel just shows "not configured" and the admin can re-import.
- **Clear**: delete the manifest key first (atomically "gone" from the reader's point
  of view), then best-effort delete the numbered chunks (ignore individual failures —
  they're now harmless orphans).
- Implement once as a private generic helper (`writeChunked(String baseKey, String content)`,
  `Optional<String> readChunked(String baseKey)`, `deleteChunked(String baseKey)`)
  reused for all three credential fields. `projectId` will always resolve to a single
  chunk; keeping it on the same generic path avoids a surprise failure if a value ever
  grows past 1000 chars.

**Wiring**: `configuration.CredentialStorageConfiguration.credentialStoragePort()`
currently hardcodes `new InMemoryCredentialStorageAdapter()` with a
`// TODO(stage 4)` marker — replace with an OS branch:
```java
return isWindows() ? new WindowsCredentialManagerAdapter() : new InMemoryCredentialStorageAdapter();
```
(`System.getProperty("os.name", "").toLowerCase().startsWith("windows")`, as noted in
the TODO comment already in that file.) This is what keeps
`GdriveBackupApplicationTest` (runs on `ubuntu-latest` in CI) booting clean on Linux
with the in-memory adapter, while production on Windows gets the real one — first-run
behavior (manifest absent → `Optional.empty()`) is uniform across both adapters.

**pom.xml**: add the `com.microsoft:credential-secure-storage` dependency (~1.0.3).

**Tests**: `WindowsCredentialManagerAdapterIT` (not `*Test` — this genuinely can't run
on `ubuntu-latest`), guarded by JUnit 5 `@EnabledOnOs(OS.WINDOWS)`, matching the repo's
`*IT`/opt-in convention (`AGENTS.md`: Failsafe runs `*IT` during `verify`; "Keep
real-account OAuth tests opt-in"). Cover:
- round-trip of a value under 1000 chars (single chunk)
- round-trip forcing 3+ chunks, byte-exact concatenation
- clear removes the manifest and the app reports "not configured" afterward

Additionally unit-test the rollback logic itself (the part that deletes
already-written chunks on a failed `add()`) with a fake `SecretStore<StoredCredential>`
that returns `false` on the 2nd `add()` call — that branch shouldn't only be covered by
the Windows-only IT, since it's the crash-safety-critical path.

**Manual verification on Windows**: import both credential files through the Settings
modal, restart the app, confirm they're still configured (persisted across restarts)
and sign-in/backup still work; inspect Credential Manager
(`rundll32.exe keymgr.dll, KRShowKeyMgr` or Control Panel → Credential Manager →
Windows Credentials) to confirm entries appear under the `gdrive-backup/*` names and
no plaintext secret is visible outside the app.

## Stage 5 — jpackage Windows installer

`pom.xml` changes:
- Add explicit `javafx-graphics` and `javafx-base` dependencies (currently only
  `javafx-controls`, which pulls these in transitively via its own POM — making them
  explicit documents the real module surface for jlink/jpackage's module computation).
- Add `org.panteleyev:jpackage-maven-plugin` (~1.8.0) — preferred over `launch4j`
  (wraps a jar in an .exe launcher but doesn't bundle a JRE or produce an MSI) and over
  a raw `exec-maven-plugin` invocation (this plugin gives a proper `<configuration>`
  block integrated with the `package` phase). Configure `type=MSI` (or `EXE`),
  `mainJar` = the Spring Boot fat jar (`${project.build.finalName}.jar`),
  `mainClass=org.nm.gdrive_backup.GdriveBackupApplication`, `winMenu`/`winShortcut=true`,
  bound after `spring-boot:repackage`.
- Module list (`addModules`): compute once via
  `jdeps --multi-release 25 --print-module-deps --ignore-missing-deps target/gdrive-backup-*.jar`
  against the built fat jar and hardcode the result. Expect at least: `java.base`,
  `java.desktop` (JavaFX + `java.awt.Desktop`, used by
  `JavaFxApplication.openBrowser()`), `java.sql` (sqlite-jdbc), `java.naming`,
  `java.management`, `java.logging`, `jdk.crypto.ec` (TLS for Google API HTTPS calls),
  `javafx.controls`/`javafx.graphics`/`javafx.base`.
- Scope the jpackage plugin execution to a `windows-package` Maven profile activated
  only on Windows (`<activation><os><family>windows</family></os></activation>`), so
  `./mvnw verify` on the existing Linux CI job (`.github/workflows/ci.yml`) is
  unaffected — jpackage doesn't cross-compile; it bundles the *host's* own JRE/natives.
- `org.xerial:sqlite-jdbc` already bundles its native libs inside its own jar and
  extracts them at runtime — no special jpackage handling needed, just confirm at
  manual-verification time.

**CI**: add a second job to `.github/workflows/ci.yml`, `runs-on: windows-latest`,
running `./mvnw -B -P windows-package package` and uploading the resulting MSI/EXE via
`actions/upload-artifact@v4` — **not** a required/blocking check, just a continuous
"does it build" signal. Full clean-machine install/run verification stays a manual
step; per `claude-models.md`, "verifying on a clean machine without a JDK is a manual
step no model can do."

**Manual verification**: run the Windows CI job (or build locally on Windows), download
the artifact, install it on a real Windows machine — ideally a clean VM with no JDK —
launch it, and walk through Settings → import credentials → sign in → run a backup.

## After stage 5

Update `docs/gdrive-backup-app.md`: flip the P3 "Windows installer and clean-machine
verification" item from `[ ]` to `[x]`, add a one-line progress note in the Completed
section (mirroring the style of the other entries there), bump `Last reviewed`, and
delete this file — its content will be fully superseded by the shipped code and the
roadmap entry.
