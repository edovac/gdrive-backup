# Windows credential storage and packaging — remaining work

This documents the rest of the plan for removing `application.properties`/env-var
configuration and shipping a self-contained Windows package. Stages 1–5 are all done
(see below); what's left is a clean-machine manual verification pass, which needs a
second, real Windows machine and can't be done from this session. Once that's done,
fold a short summary back into `docs/gdrive-backup-app.md`'s P3 "Windows installer and
clean-machine verification" item and delete this file.

## Why

The app moved from a dev-only `.env`/env-var setup
(`GOOGLE_SERVICE_ACCOUNT_KEY`, `GOOGLE_OAUTH_CLIENT_SECRETS`, `GOOGLE_CLOUD_PROJECT_ID`,
all file paths) to a packaged, personal-use Windows distribution. There's no shell to
export env vars from on a packaged install, and per `AGENTS.md`, credential files
can't be stored in plain files either way. Decisions already made (see git history on
`feature/windows` for the full discussion): secrets go into **Windows Credential
Manager**; a **Settings modal** replaces manual file/env setup; the package is a
**self-contained app-image with a bundled, trimmed JRE** via `jpackage` — not an
installer (see stage 5 below for why).

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

## Done (stage 5) — self-contained app-image, not an installer

The original plan here was an MSI installer via `jpackage`. Building one hands-on hit
a real blocker: MSI output needs the **WiX Toolset**, an external tool that isn't part
of the JDK and wasn't installed. Asked whether an installer was actually required, the
decision was to skip installers entirely and ship a **self-contained `app-image`**
instead: a folder with a native `.exe` launcher and a bundled, trimmed JRE, buildable
with zero extra tooling (no WiX, no Inno Setup). Distribute it by zipping the folder;
running the `.exe` inside launches the app directly, no install step. The tradeoff is
losing installer niceties (Start Menu entry, desktop shortcut, an Apps & Features
uninstall entry) — revisit with WiX/Inno Setup later if that's ever worth it.

- **`pom.xml`**: added explicit `javafx-graphics`/`javafx-base` dependencies (were only
  transitive via `javafx-controls`). Added `org.panteleyev:jpackage-maven-plugin:1.8.0`
  (preferred over `launch4j`, which doesn't bundle a JRE, and over a raw
  `exec-maven-plugin` call). Both scoped to a `windows-package` Maven profile activated
  only on `<os><family>windows</family></os>`, so the Linux CI job is unaffected —
  jpackage doesn't cross-compile, it bundles the *host's* own JRE/natives.
- **Staging** (two steps ahead of the `jpackage` execution, all bound to the `package`
  phase so ordering follows POM declaration order after `spring-boot:repackage`):
  - `maven-resources-plugin` copies just the repackaged fat jar into
    `target/jpackage-input` — jpackage's `--input` directory gets copied wholesale into
    the app image, so this avoids dragging in the much larger, mostly irrelevant rest
    of `target/`.
  - `maven-dependency-plugin` copies the three Windows-**classified** (`-win`) JavaFX
    jars into `target/jpackage-modules`, given explicitly to jpackage as
    `modulePaths`. This turned out to be necessary, not optional: `javafx-controls`
    (and `-graphics`/`-base`) pull in a same-named `-win` classified artifact
    transitively via their own POM's OS-activated profile, and **only the classified
    jar has a real `module-info.class`** — the unclassified one has none. Pointing
    jlink at `BOOT-INF/lib` (which has both) left it unable to resolve `javafx.base`/
    `controls`/`graphics` as named modules at all (`jlink failed with: Error: Module
    javafx.base not found`); isolating just the classified jars on their own module
    path fixed it.
  - `mainJar` is set to the fat jar's filename with **no `mainClass` override** —
    jpackage reads `Main-Class` from the jar's own manifest
    (`org.springframework.boot.loader.launch.JarLauncher`, which is what actually
    unpacks `BOOT-INF/lib` at runtime). Setting `mainClass` directly to
    `GdriveBackupApplication`, as the original plan sketched, would have skipped that
    and broken the packaged app's classpath.
- **Module list (`addModules`)**: computed by running jdeps' "print module deps" mode
  against `BOOT-INF/classes` of the *exploded* fat jar (jdeps can't see into a Spring
  Boot fat jar's nested `BOOT-INF/lib` when pointed at the jar directly — it silently
  under-reports), with `BOOT-INF/lib`'s jars minus the unclassified javafx duplicates
  on the classpath. Result: `java.base`, `java.compiler`, `java.desktop`,
  `java.instrument`, `java.logging`, `java.management`, `java.naming`, `java.prefs`,
  `java.scripting`, `java.security.jgss`, `java.sql`, `jdk.httpserver`, `jdk.jfr`,
  `jdk.unsupported` — a broader, empirically-grounded set than the original guess,
  since jdeps' recursive closure picks up optional-API references from third-party
  libraries (Guava, gRPC, Apache HttpClient, etc.) the original plan didn't anticipate.
  Unioned by hand with two kinds of module jdeps cannot find by static analysis alone:
  `javafx.base`/`controls`/`graphics` (loaded via the JavaFX runtime, not a bytecode
  `requires`) and `jdk.crypto.ec` (EC/ECDSA TLS cipher suites for Google's HTTPS APIs,
  selected reflectively via the security-provider SPI).
- `org.xerial:sqlite-jdbc` bundles its native libs inside its own jar and extracts
  them at runtime — confirmed working with no special jpackage handling.
- **CI**: added a `windows-package` job to `.github/workflows/ci.yml`
  (`runs-on: windows-latest`, `continue-on-error: true` since it's a "does it still
  build" signal, not a required check) that runs
  `./mvnw.cmd -B -P windows-package -DskipTests package` and uploads
  `target/app-image/Google Drive Backup` via `actions/upload-artifact@v4`.
- **Verified on this machine**: `./mvnw -DskipTests -P windows-package package`
  succeeds; the produced `Google Drive Backup.exe` launches the trimmed runtime with
  no missing-module errors — Spring Boot starts, JNA loads the Windows Credential
  Manager native library, JavaFX graphics natives load, all against the 141MB
  app-image folder.

**Still to verify manually**: copy/zip `target/app-image/Google Drive Backup` (or the
CI job's uploaded artifact) to another Windows machine — ideally a clean VM with no
JDK, to confirm the bundled runtime is genuinely self-contained — and confirm
`Google Drive Backup.exe` launches there too, then walk through Settings → import
credentials → sign in → run a backup. Per `claude-models.md`, "verifying on a clean
machine without a JDK is a manual step no model can do."

## After stage 5

Update `docs/gdrive-backup-app.md`: flip the P3 "Windows installer and clean-machine
verification" item from `[ ]` to `[x]`, add a one-line progress note in the Completed
section (mirroring the style of the other entries there), bump `Last reviewed`, and
delete this file — its content will be fully superseded by the shipped code and the
roadmap entry.
