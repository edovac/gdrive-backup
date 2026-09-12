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

CREATE TABLE IF NOT EXISTS files (
    file_id TEXT PRIMARY KEY,
    owner_scope TEXT NOT NULL,
    name TEXT NOT NULL,
    parents TEXT NOT NULL,
    drive_id TEXT,
    mime_type TEXT NOT NULL,
    trashed INTEGER NOT NULL DEFAULT 0,
    head_revision_id TEXT,
    current_version_id INTEGER
);

CREATE TABLE IF NOT EXISTS file_versions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    file_id TEXT NOT NULL REFERENCES files(file_id),
    revision_id TEXT NOT NULL,
    timestamp TEXT NOT NULL,
    local_path TEXT NOT NULL,
    size_bytes INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS file_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    file_id TEXT NOT NULL REFERENCES files(file_id),
    event_type TEXT NOT NULL,
    old_value TEXT,
    new_value TEXT,
    timestamp TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sync_state (
    scope_key TEXT PRIMARY KEY,
    page_token TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_file_versions_file_id ON file_versions(file_id);
CREATE INDEX IF NOT EXISTS idx_file_events_file_id ON file_events(file_id);