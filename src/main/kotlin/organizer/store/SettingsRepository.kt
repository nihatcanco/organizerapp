package organizer.store

import organizer.db.Database
import organizer.db.execute
import organizer.db.queryOne

/** Settings made on the Settings page, by name. Secret values are stored as [SecretBox] sealed them. */
class SettingsRepository(private val db: Database) {

    suspend fun get(name: String): String? = db.read { c ->
        c.queryOne("SELECT value FROM settings WHERE name = ?", name) { it.getString(1) }
    }

    suspend fun set(name: String, value: String) {
        db.write { c ->
            c.execute("INSERT INTO settings (name, value) VALUES (?, ?) ON CONFLICT (name) DO UPDATE SET value = excluded.value", name, value)
        }
    }

    suspend fun remove(name: String) {
        db.write { c -> c.execute("DELETE FROM settings WHERE name = ?", name) }
    }
}
