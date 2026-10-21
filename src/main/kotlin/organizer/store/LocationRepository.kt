package organizer.store

import organizer.db.Database
import organizer.db.execute
import organizer.db.getLongOrNull
import organizer.db.query
import organizer.db.queryOne
import organizer.model.Location
import organizer.model.LocationDraft
import organizer.model.LocationTree
import java.sql.ResultSet
import java.time.Instant

class LocationRepository(private val db: Database) {

    suspend fun tree(): LocationTree = db.read { c ->
        val counts = c.query("SELECT location_id, count(*) FROM items WHERE location_id IS NOT NULL GROUP BY location_id") {
            it.getLong(1) to it.getInt(2)
        }
        LocationTree(c.query("SELECT * FROM locations", map = ::toLocation), counts.toMap())
    }

    suspend fun get(id: Long): Location? = db.read { c ->
        c.queryOne("SELECT * FROM locations WHERE id = ?", id, map = ::toLocation)
    }

    /** Creates all [drafts] in one transaction and returns their ids in the same order. */
    suspend fun create(drafts: List<LocationDraft>): List<Long> = db.write { c ->
        val now = System.currentTimeMillis()
        drafts.map { draft ->
            c.queryOne(
                "INSERT INTO locations (parent_id, name, description, created_at, updated_at) VALUES (?, ?, ?, ?, ?) RETURNING id",
                draft.parentId, draft.name, draft.description, now, now,
            ) { it.getLong(1) }!!
        }
    }

    suspend fun create(draft: LocationDraft): Long = create(listOf(draft)).single()

    suspend fun update(id: Long, draft: LocationDraft): Boolean = db.write { c ->
        if (draft.parentId != null) {
            val parentIsInside = c.queryOne(
                """
                WITH RECURSIVE ancestors(id, parent_id) AS (
                    SELECT id, parent_id FROM locations WHERE id = ?
                    UNION
                    SELECT l.id, l.parent_id FROM locations l JOIN ancestors a ON l.id = a.parent_id
                )
                SELECT 1 FROM ancestors WHERE id = ?
                """,
                draft.parentId, id,
            ) { true } ?: false
            require(!parentIsInside) { "A location can't be moved inside itself." }
        }
        c.execute(
            "UPDATE locations SET parent_id = ?, name = ?, description = ?, updated_at = ? WHERE id = ?",
            draft.parentId, draft.name, draft.description, System.currentTimeMillis(), id,
        ) > 0
    }

    /** Deletes the location. Its items and sub-locations move up to its parent rather than being lost. */
    suspend fun delete(id: Long): Boolean = db.write { c ->
        val location = c.queryOne("SELECT * FROM locations WHERE id = ?", id, map = ::toLocation) ?: return@write false
        c.execute("UPDATE locations SET parent_id = ? WHERE parent_id = ?", location.parentId, id)
        c.execute(
            "UPDATE items SET location_id = ?, updated_at = ? WHERE location_id = ?",
            location.parentId, System.currentTimeMillis(), id,
        )
        c.execute("DELETE FROM locations WHERE id = ?", id) > 0
    }
}

private fun toLocation(row: ResultSet) = Location(
    id = row.getLong("id"),
    parentId = row.getLongOrNull("parent_id"),
    name = row.getString("name"),
    description = row.getString("description"),
    createdAt = Instant.ofEpochMilli(row.getLong("created_at")),
    updatedAt = Instant.ofEpochMilli(row.getLong("updated_at")),
)
