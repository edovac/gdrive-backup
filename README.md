# Google Workspace Drive Backup

A Windows desktop app that backs up the Google Drive data of a Google Workspace
organization (personal drives and Shared Drives) to local disks, typically
external hard drives. The admin signs in with Google, picks the drives to back
up and runs a full or incremental backup. Each run writes a ZIP archive per
drive. A full archive is a Drive-shaped folder tree that can be uploaded
straight to a new Drive, and incremental archives can be merged back into a
full one without contacting Google.

Built with Java 25, Spring Boot, JavaFX and SQLite. Restore is manual (upload
an extracted full archive); scheduled backups are out of scope.

## License

Licensed under the [Apache License, Version 2.0](LICENSE). Unless required by
applicable law or agreed to in writing, the software is distributed on an
"AS IS" basis, without warranties or conditions of any kind.

## Documentation

- [Requirements spec](docs/gdrive-backup-app.md): behavior, auth model, data
  model, sync algorithm, archive format, UI and known limitations.
- [Google credentials setup](docs/google-admin-console-setup.md): Cloud
  project, OAuth client, service account and domain-wide delegation.
- [Roadmap](docs/plan.md): what is built and what is next.
- [AGENTS.md](AGENTS.md) and [CLAUDE.md](CLAUDE.md): architecture rules and
  codebase guide for contributors and coding agents.

## Download

Windows builds are published on
[GitHub Releases](https://github.com/edovac/gdrive-backup/releases) as a zip
holding a self-contained app-image (`.exe` launcher plus bundled Java
runtime). Unzip and run the `.exe`; no JDK is needed.

## Credentials

The app reads no credentials from files or environment variables. After the
Google setup, import the service-account key and the OAuth client secrets in
the app's **Settings** dialog; they are stored in Windows Credential Manager.
Never commit keys, client secrets or tokens to this repository.

## Build and run

Use the Maven wrapper from the repository root:

```bash
./mvnw test                 # unit tests
./mvnw verify               # also the opt-in integration tests (*IT)
./mvnw spring-boot:run      # launch the app
./mvnw.cmd -P windows-package -DskipTests -Dapp.version=0.1.0 package   # Windows app-image
```

Integration tests run against real Google accounts and are skipped unless
enabled; see [CLAUDE.md](CLAUDE.md#commands).
