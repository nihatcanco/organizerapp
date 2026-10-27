package organizer.db

/**
 * Schema history, one list of statements per version (tracked in `PRAGMA user_version`).
 * Only ever append: existing databases have already run the earlier entries.
 */
internal val MIGRATIONS: List<List<String>> = listOf(
    listOf(
        // AUTOINCREMENT: ids are printed in QR labels, so a deleted id must never be handed out again.
        """
        CREATE TABLE locations (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            parent_id   INTEGER REFERENCES locations (id),
            name        TEXT    NOT NULL,
            description TEXT    NOT NULL DEFAULT '',
            created_at  INTEGER NOT NULL,
            updated_at  INTEGER NOT NULL
        )
        """,
        "CREATE INDEX locations_parent ON locations (parent_id)",
        """
        CREATE TABLE items (
            id           INTEGER PRIMARY KEY AUTOINCREMENT,
            location_id  INTEGER REFERENCES locations (id) ON DELETE SET NULL,
            name         TEXT    NOT NULL,
            quantity     INTEGER NOT NULL DEFAULT 1 CHECK (quantity >= 0),
            min_quantity INTEGER CHECK (min_quantity >= 0),
            notes        TEXT    NOT NULL DEFAULT '',
            search_text  TEXT    NOT NULL DEFAULT '',
            created_at   INTEGER NOT NULL,
            updated_at   INTEGER NOT NULL
        )
        """,
        "CREATE INDEX items_location ON items (location_id)",
        """
        CREATE TABLE tags (
            id         INTEGER PRIMARY KEY,
            name       TEXT NOT NULL,
            normalized TEXT NOT NULL UNIQUE
        )
        """,
        """
        CREATE TABLE item_tags (
            item_id INTEGER NOT NULL REFERENCES items (id) ON DELETE CASCADE,
            tag_id  INTEGER NOT NULL REFERENCES tags (id) ON DELETE CASCADE,
            PRIMARY KEY (item_id, tag_id)
        ) WITHOUT ROWID
        """,
        "CREATE INDEX item_tags_tag ON item_tags (tag_id)",
        """
        CREATE TABLE item_properties (
            item_id  INTEGER NOT NULL REFERENCES items (id) ON DELETE CASCADE,
            position INTEGER NOT NULL,
            name     TEXT    NOT NULL,
            value    TEXT    NOT NULL,
            PRIMARY KEY (item_id, position)
        ) WITHOUT ROWID
        """,
        // Small columns come first so SQLite can read them without walking the blobs' overflow pages.
        """
        CREATE TABLE item_photos (
            item_id      INTEGER PRIMARY KEY REFERENCES items (id) ON DELETE CASCADE,
            content_type TEXT    NOT NULL,
            updated_at   INTEGER NOT NULL,
            thumbnail    BLOB,
            image        BLOB    NOT NULL
        )
        """,
    ),
    listOf(
        // App settings made on the Settings page; secret ones are stored encrypted (see store/SecretBox.kt).
        """
        CREATE TABLE settings (
            name  TEXT PRIMARY KEY,
            value TEXT NOT NULL
        ) WITHOUT ROWID
        """,
    ),
)
