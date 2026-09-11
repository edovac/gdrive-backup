## Architecture

- Structure the application using hexagonal architecture (ports and adapters).
- Keep domain and application use-case logic independent of JavaFX, HTTP, Spring MVC, and other delivery mechanisms.
- Define inbound ports for operations such as browsing Drive data, starting backups, and querying history; implement JavaFX as an inbound adapter.
- Define outbound ports for Google Drive access, credential handling, persistence, local file storage, scheduling, and progress/reporting; keep their implementations outside the core.
- A future pure HTTP service must be able to replace or coexist with the JavaFX adapter without changing domain rules or application use cases.
- Do not let controllers, UI models, REST resources, Google SDK types, or SQLite/JPA types leak into the domain model or core use-case APIs.
