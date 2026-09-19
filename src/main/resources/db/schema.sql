CREATE TABLE IF NOT EXISTS users (
    email TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    last_synced_at TEXT
);

CREATE TABLE IF NOT EXISTS drives (
    drive_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    last_synced_at TEXT
);

CREATE TABLE IF NOT EXISTS archives (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    scope_key TEXT NOT NULL,
    sequence_number INTEGER NOT NULL,
    base_archive_id INTEGER REFERENCES archives(id),
    mode TEXT NOT NULL,
    revision_mode TEXT NOT NULL,
    created_at TEXT NOT NULL,
    archive_path TEXT,
    from_page_token TEXT,
    to_page_token TEXT,
    cancelled INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS archive_sources (
    archive_id INTEGER NOT NULL REFERENCES archives(id),
    source_archive_id INTEGER NOT NULL REFERENCES archives(id),
    PRIMARY KEY (archive_id, source_archive_id)
);

CREATE TABLE IF NOT EXISTS files (
    file_id TEXT PRIMARY KEY,
    owner_scope TEXT NOT NULL,
    name TEXT NOT NULL,
    parents TEXT NOT NULL,
    drive_id TEXT,
    mime_type TEXT NOT NULL,
    trashed INTEGER NOT NULL DEFAULT 0,
    head_revision_id TEXT,
    current_version_id INTEGER REFERENCES file_captures(id)
);

CREATE TABLE IF NOT EXISTS file_captures (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    file_id TEXT NOT NULL REFERENCES files(file_id),
    revision_id TEXT NOT NULL,
    timestamp TEXT NOT NULL,
    archive_id INTEGER NOT NULL REFERENCES archives(id),
    entry_name TEXT NOT NULL,
    size_bytes INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS file_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    file_id TEXT NOT NULL REFERENCES files(file_id),
    event_type TEXT NOT NULL,
    old_value TEXT,
    new_value TEXT,
    timestamp TEXT NOT NULL,
    archive_id INTEGER NOT NULL REFERENCES archives(id)
);

CREATE TABLE IF NOT EXISTS sync_state (
    scope_key TEXT PRIMARY KEY,
    page_token TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_file_captures_file_id ON file_captures(file_id);
CREATE INDEX IF NOT EXISTS idx_file_events_file_id ON file_events(file_id);
CREATE INDEX IF NOT EXISTS idx_archives_scope_key ON archives(scope_key);
