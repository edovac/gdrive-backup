## Architecture

- Structure the application using hexagonal architecture (ports and adapters).
- Keep domain and application use-case logic independent of JavaFX, HTTP, Spring MVC, and other delivery mechanisms.
- Define inbound ports for operations such as browsing Drive data, starting backups, and querying history; implement JavaFX as an inbound adapter.
- Define outbound ports for Google Drive access, credential handling, persistence, local file storage, scheduling, and progress/reporting; keep their implementations outside the core.
- A future pure HTTP service must be able to replace or coexist with the JavaFX adapter without changing domain rules or application use cases.
- Do not let controllers, UI models, REST resources, Google SDK types, or SQLite/JPA types leak into the domain model or core use-case APIs.

## Build and Test

- Use the Maven wrapper from the repository root; the project targets Java 25 and Spring Boot 4.1.1.
- Run `./mvnw test` for the full test suite and `./mvnw spring-boot:run` to start the application locally.
- Keep production code under `src/main/java` and tests under `src/test/java`.
- The current Java package is `org.nm.gdrive_backup`; preserve the underscore because the original hyphenated package name is invalid.

## Project Context

- The product requirements, planned Google Drive sync algorithm, data model, security constraints, and build order are documented in [gdrive-backup-app.md](gdrive-backup-app.md).
- Keep service-account domain-wide delegation as the backend data path and OAuth as the admin UI access gate; do not introduce a second Drive-fetching path for previews.
- Treat service-account keys and OAuth tokens as sensitive credentials: never commit them or store them in plain files.
- The repository is still a Spring Boot skeleton. Add domain/application ports before framework adapters, and add focused tests for use cases and adapter boundaries as features are implemented.

## Repository Notes

- `README.md` and `HELP.md` are generated starter documentation and may contain stale Gradle references; use `pom.xml` and the Maven wrapper as the source of truth for builds.
