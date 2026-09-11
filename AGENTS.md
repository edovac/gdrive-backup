## Architecture

- Structure the application using hexagonal architecture (ports and adapters).
- Use `org.nm.gdrive_backup.domain` for framework-independent business code: put domain objects in `domain.model`, inbound ports in `domain.port.in`, outbound ports in `domain.port.out`, and domain/application services in `domain.service`.
- Use `org.nm.gdrive_backup.adapter` for delivery and infrastructure implementations: put inbound adapters in `adapter.in` and outbound adapters in `adapter.out`.
- Keep Spring configuration that composes the application globally in `org.nm.gdrive_backup.configuration`.
- Keep Spring configuration for beans owned by one adapter in that adapter's `configuration` package, such as `org.nm.gdrive_backup.adapter.configuration` or a more specific adapter configuration package.
- Keep domain and application use-case logic independent of JavaFX, HTTP, Spring MVC, and other delivery mechanisms.
- Define inbound ports for operations such as browsing Drive data, starting backups, and querying history; implement JavaFX as an inbound adapter.
- Define outbound ports for Google Drive access, credential handling, persistence, local file storage, scheduling, and progress/reporting; keep their implementations outside the core.
- Keep Google login behind `domain.port.in.GoogleLoginUseCase` and `domain.port.out.GoogleOAuthPort`; expose only opaque login sessions from the domain and keep Google SDK credentials inside the adapter.
- Use the OAuth login only as the admin UI access gate with the `drive.readonly` scope. Use service-account domain-wide delegation for org-wide Drive access and previews; do not use the admin OAuth token for impersonation or backup data retrieval.
- Keep browser authorization approval in the inbound UI adapter. The Google OAuth adapter may provide the authorization URI through `GoogleAuthorizationApproval`, but it must not require logs as the user-facing browser-launch mechanism.
- Keep platform-specific browser launching, including WSL-to-Windows interop, in the JavaFX adapter rather than in domain or Google OAuth code.
- A future pure HTTP service must be able to replace or coexist with the JavaFX adapter without changing domain rules or application use cases.
- Do not let controllers, UI models, REST resources, Google SDK types, or SQLite/JPA types leak into the domain model or core use-case APIs.

## Build and Test

- Use the Maven wrapper from the repository root; the project targets Java 25 and Spring Boot 4.1.1.
- Run `./mvnw test` for the full test suite and `./mvnw spring-boot:run` to start the application locally.
- Name unit test classes with the `*Test` suffix and real external integration test classes with the `*IT` suffix. Surefire runs `*Test`; Failsafe runs `*IT` during `verify`.
- Keep real-account OAuth tests opt-in and never commit client-secrets JSON, access tokens, refresh tokens, or service-account keys.
- Keep production code under `src/main/java` and tests under `src/test/java`.
- The current Java package is `org.nm.gdrive_backup`; preserve the underscore because the original hyphenated package name is invalid.

## Project Context

- The product requirements, planned Google Drive sync algorithm, data model, security constraints, and build order are documented in [gdrive-backup-app.md](gdrive-backup-app.md).
- Keep service-account domain-wide delegation as the backend data path and OAuth as the admin UI access gate; do not introduce a second Drive-fetching path for previews.
- Treat service-account keys and OAuth tokens as sensitive credentials: never commit them or store them in plain files.
- Add domain/application ports before framework adapters, and add focused tests for use cases and adapter boundaries as features are implemented.

## Repository Notes

- `README.md` and `HELP.md` are generated starter documentation and may contain stale Gradle references; use `pom.xml` and the Maven wrapper as the source of truth for builds.
