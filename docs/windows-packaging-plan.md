# Windows credential storage and packaging — remaining work

This documents the rest of the plan for removing `application.properties`/env-var
configuration and shipping a Windows installer. Stages 1–4 are done (see below);
stage 5 is Windows-only and needs a real Windows machine to build and verify, so
it's written up here to resume in that environment. Once stage 5 lands, fold a
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

## Done (stage 4)

- **`adapter.out.credentialstorage.WindowsCredentialManagerAdapter implements
  CredentialStoragePort`**, using `com.microsoft:credential-secure-storage:1.0.3`'s
  `StorageProvider.getCredentialStorage(true, SecureOption.REQUIRED)`. Reuses
  `CredentialFileValidation` for both `validate*` methods, same as the in-memory adapter.
- **Chunking**, built from scratch (the library has none): target-name scheme
  `gdrive-backup/service-account-key`, `gdrive-backup/oauth-client-secrets`,
  `gdrive-backup/project-id`, each split into `<base>/chunk-0`, `<base>/chunk-1`, … plus
  a `<base>/manifest` entry holding the chunk count. `CHUNK_CHAR_LIMIT = 1000` UTF-16
  chars per chunk — comfortably under the library's own 2047-char `StoredCredential`
  password limit (the actual constraint; the original ~2560-byte
  `CRED_MAX_CREDENTIAL_BLOB_SIZE` estimate was in the right ballpark but the library
  validates its own, smaller limit before any native call happens).
- **Write ordering (crash-safety)**: numbered chunks first, manifest last; a failed
  `add()` rolls back whatever this import already wrote and throws
  `WindowsCredentialStorageException` (mirrors `GoogleOAuthException`'s style). **Extra
  hygiene beyond the original plan**: on a successful re-import with fewer chunks than
  the previous value, the now-orphaned trailing chunks from the old value are deleted
  too, once the new manifest is safely committed — otherwise old secret bytes (e.g. a
  replaced service-account key) would sit unreferenced but still readable in Credential
  Manager indefinitely.
- **Read**: manifest absent → `Optional.empty()`; a missing expected chunk (corrupted
  state) is also treated as absent rather than thrown.
- **Clear**: deletes the manifest first, then best-effort deletes the numbered chunks.
- **Test isolation**: the constructor takes an injectable key prefix
  (package-private `WindowsCredentialManagerAdapter(SecretStore<StoredCredential> store,
  String keyPrefix)`), not in the original plan. Without it, `WindowsCredentialManagerAdapterIT`
  would exercise the real Credential Manager under the exact same `gdrive-backup/*`
  names the packaged app uses, risking overwriting (and then clearing) a developer's own
  configured credentials on `./mvnw verify`. The IT uses `gdrive-backup-it/*` instead.
- **Wiring**: `configuration.CredentialStorageConfiguration.credentialStoragePort()`
  branches on `System.getProperty("os.name", "")`
  (`return isWindows() ? new WindowsCredentialManagerAdapter() : new InMemoryCredentialStorageAdapter();`),
  so `GdriveBackupApplicationTest` still boots clean on `ubuntu-latest` CI with the
  in-memory adapter while this Windows dev machine now wires the real one.
- **Tests**: `WindowsCredentialManagerAdapterTest` (unit, fake `SecretStore`) covers
  round-tripping under/over one chunk, chunk-write rollback, manifest-write rollback,
  orphaned-chunk cleanup on shrink, clear, a missing chunk read as absent, and nothing
  stored read as absent. `WindowsCredentialManagerAdapterIT`, guarded by
  `@EnabledOnOs(OS.WINDOWS)`, round-trips a single-chunk and a 3+-chunk value and checks
  clear against the **real** Windows Credential Manager on this machine — all 3 passed.
  Full `./mvnw verify` (313 unit tests + this machine's Windows ITs) is green.

**Still to verify manually**: importing both credential files through the actual
Settings modal, restarting the app, and confirming sign-in/backup still work end to end
(the IT covers the storage layer directly, not the UI round trip).

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
