package organizer.store

import organizer.db.Database
import organizer.db.execute
import organizer.db.getIntOrNull
import organizer.db.getLongOrNull
import organizer.db.query
import organizer.db.queryOne
import organizer.model.Item
import organizer.model.ItemDraft
import organizer.model.ItemQuery
import organizer.model.MAX_QUANTITY
import organizer.model.NaturalOrder
import organizer.model.Photo
import organizer.model.PhotoChange
import organizer.model.PhotoUpload
import organizer.model.Property
import organizer.model.SearchPage
import organizer.model.Stats
import organizer.model.Stock
import organizer.model.TagCount
import organizer.model.normalizeForSearch
import organizer.model.searchTerms
import java.sql.Connection
import java.time.Instant

class ItemRepository(private val db: Database) {

    suspend fun get(id: Long): Item? = db.read { c -> c.loadItems(listOf(id)).firstOrNull() }

    suspend fun inLocation(locationId: Long): List<Item> = db.read { c ->
        c.loadItems(c.query("SELECT id FROM items WHERE location_id = ?", locationId) { it.getLong(1) })
    }.sortedWith(compareBy(NaturalOrder) { it.name })

    suspend fun all(): List<Item> = db.read { c ->
        c.loadItems(c.query("SELECT id FROM items") { it.getLong(1) })
    }.sortedWith(compareBy(NaturalOrder) { it.name })

    suspend fun recentlyUpdated(limit: Int): List<Item> = db.read { c ->
        c.loadItems(c.query("SELECT id FROM items ORDER BY updated_at DESC, id DESC LIMIT ?", limit) { it.getLong(1) })
    }

    /**
     * Finds items containing every word of the query anywhere in their name, tags, properties or notes
     * (so "317" finds "LM317T"), best name matches first. Only the first [limit] are loaded.
     */
    suspend fun search(query: ItemQuery, limit: Int): SearchPage = db.read { c ->
        val terms = searchTerms(query.text)
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        for (term in terms) {
            conditions += "instr(search_text, ?) > 0"
            params += term
        }
        if (query.tag != null) {
            conditions += "id IN (SELECT it.item_id FROM item_tags it JOIN tags t ON t.id = it.tag_id WHERE t.normalized = ?)"
            params += normalizeForSearch(query.tag)
        }
        if (query.lowStock) conditions += "min_quantity IS NOT NULL AND quantity <= min_quantity"
        if (query.unsorted) conditions += "location_id IS NULL"
        val where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")

        val matches = c.query("SELECT id, name FROM items $where", *params.toTypedArray()) { row ->
            val name = row.getString(2)
            Match(row.getLong(1), name, rank(normalizeForSearch(name), terms))
        }
        val ranked = matches.sortedWith(compareBy<Match> { it.rank }.thenBy(NaturalOrder) { it.name })
        SearchPage(c.loadItems(ranked.take(limit).map { it.id }), matches.size)
    }

    suspend fun stats(): Stats = db.read { c ->
        c.queryOne(
            """
            SELECT (SELECT count(*) FROM items),
                   (SELECT count(*) FROM locations),
                   (SELECT count(*) FROM items WHERE min_quantity IS NOT NULL AND quantity <= min_quantity),
                   (SELECT count(*) FROM items WHERE location_id IS NULL)
            """,
        ) { Stats(it.getInt(1), it.getInt(2), it.getInt(3), it.getInt(4)) }!!
    }

    suspend fun tags(): List<TagCount> = db.read { c ->
        c.query("SELECT t.name, count(*) FROM tags t JOIN item_tags it ON it.tag_id = t.id GROUP BY t.id") {
            TagCount(it.getString(1), it.getInt(2))
        }
    }.sortedWith(compareBy(NaturalOrder) { it.name })

    /** Names of existing items, offered as suggestions while typing a name in the item form. */
    suspend fun names(): List<String> = db.read { c ->
        c.query("SELECT name FROM items GROUP BY name ORDER BY max(updated_at) DESC LIMIT 1000") { it.getString(1) }
    }.sortedWith(NaturalOrder)

    /** Property names already in use, most common first, offered as suggestions in the item form. */
    suspend fun propertyNames(): List<String> = db.read { c ->
        c.query("SELECT name FROM item_properties GROUP BY name ORDER BY count(*) DESC, name LIMIT 200") { it.getString(1) }
    }

    suspend fun create(draft: ItemDraft, photo: PhotoUpload? = null): Long = db.write { c ->
        val now = System.currentTimeMillis()
        val id = c.queryOne(
            """
            INSERT INTO items (location_id, name, quantity, min_quantity, notes, search_text, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """,
            draft.locationId, draft.name, draft.quantity, draft.minQuantity, draft.notes, draft.searchText(), now, now,
        ) { it.getLong(1) }!!
        c.saveTagsAndProperties(id, draft)
        if (photo != null) c.savePhoto(id, photo, now)
        id
    }

    suspend fun update(id: Long, draft: ItemDraft, photo: PhotoChange = PhotoChange.Keep): Boolean = db.write { c ->
        val now = System.currentTimeMillis()
        val updated = c.execute(
            """
            UPDATE items
            SET location_id = ?, name = ?, quantity = ?, min_quantity = ?, notes = ?, search_text = ?, updated_at = ?
            WHERE id = ?
            """,
            draft.locationId, draft.name, draft.quantity, draft.minQuantity, draft.notes, draft.searchText(), now, id,
        ) > 0
        if (updated) {
            c.execute("DELETE FROM item_tags WHERE item_id = ?", id)
            c.execute("DELETE FROM item_properties WHERE item_id = ?", id)
            c.saveTagsAndProperties(id, draft)
            when (photo) {
                PhotoChange.Keep -> Unit
                PhotoChange.Remove -> c.execute("DELETE FROM item_photos WHERE item_id = ?", id)
                is PhotoChange.Replace -> c.savePhoto(id, photo.upload, now)
            }
            c.deleteUnusedTags()
        }
        updated
    }

    suspend fun delete(id: Long): Boolean = db.write { c ->
        val deleted = c.execute("DELETE FROM items WHERE id = ?", id) > 0
        c.deleteUnusedTags()
        deleted
    }

    suspend fun adjustQuantity(id: Long, delta: Int): Stock? = db.write { c ->
        c.queryOne(
            """
            UPDATE items SET quantity = max(0, min(?, quantity + ?)), updated_at = ? WHERE id = ?
            RETURNING quantity, min_quantity IS NOT NULL AND quantity <= min_quantity
            """,
            MAX_QUANTITY, delta, System.currentTimeMillis(), id,
        ) { Stock(it.getInt(1), it.getBoolean(2)) }
    }

    suspend fun setQuantity(id: Long, quantity: Int): Stock? = db.write { c ->
        c.queryOne(
            """
            UPDATE items SET quantity = ?, updated_at = ? WHERE id = ?
            RETURNING quantity, min_quantity IS NOT NULL AND quantity <= min_quantity
            """,
            quantity.coerceIn(0, MAX_QUANTITY), System.currentTimeMillis(), id,
        ) { Stock(it.getInt(1), it.getBoolean(2)) }
    }

    /** The item's photo, or its small version when [thumbnail] is set (falling back to the full one). */
    suspend fun photo(id: Long, thumbnail: Boolean): Photo? = db.read { c ->
        val sql = if (thumbnail) {
            "SELECT iif(thumbnail IS NULL, content_type, 'image/jpeg'), updated_at, coalesce(thumbnail, image) FROM item_photos WHERE item_id = ?"
        } else {
            "SELECT content_type, updated_at, image FROM item_photos WHERE item_id = ?"
        }
        c.queryOne(sql, id) { Photo(it.getString(1), it.getBytes(3), it.getLong(2)) }
    }
}

private class Match(val id: Long, val name: String, val rank: Int)

/** 0: the name starts with the query, 1: the name has every word, 2: some word, 3: matched elsewhere. */
private fun rank(name: String, terms: List<String>): Int = when {
    terms.isEmpty() || name.startsWith(terms.joinToString(" ")) -> 0
    terms.all { it in name } -> 1
    terms.any { it in name } -> 2
    else -> 3
}

private fun ItemDraft.searchText(): String =
    normalizeForSearch((listOf(name) + tags + properties.flatMap { listOf(it.name, it.value) } + notes).joinToString("\n"))

/** Loads full items (with tags, properties and photo info) for [ids], keeping their order. */
private fun Connection.loadItems(ids: List<Long>): List<Item> {
    if (ids.isEmpty()) return emptyList()
    val items = HashMap<Long, Item>(ids.size)
    val tags = HashMap<Long, MutableList<String>>()
    val properties = HashMap<Long, MutableList<Property>>()
    for (chunk in ids.chunked(500)) {
        val placeholders = chunk.joinToString(",") { "?" }
        val args = chunk.toTypedArray()
        query("SELECT it.item_id, t.name FROM item_tags it JOIN tags t ON t.id = it.tag_id WHERE it.item_id IN ($placeholders)", *args) {
            tags.getOrPut(it.getLong(1)) { mutableListOf() }.add(it.getString(2))
        }
        query("SELECT item_id, name, value FROM item_properties WHERE item_id IN ($placeholders) ORDER BY item_id, position", *args) {
            properties.getOrPut(it.getLong(1)) { mutableListOf() }.add(Property(it.getString(2), it.getString(3)))
        }
        query(
            """
            SELECT i.id, i.location_id, i.name, i.quantity, i.min_quantity, i.notes, i.created_at, i.updated_at,
                   p.updated_at AS photo_version
            FROM items i LEFT JOIN item_photos p ON p.item_id = i.id
            WHERE i.id IN ($placeholders)
            """,
            *args,
        ) { row ->
            val id = row.getLong("id")
            items[id] = Item(
                id = id,
                locationId = row.getLongOrNull("location_id"),
                name = row.getString("name"),
                quantity = row.getInt("quantity"),
                minQuantity = row.getIntOrNull("min_quantity"),
                notes = row.getString("notes"),
                tags = emptyList(),
                properties = emptyList(),
                photoVersion = row.getLongOrNull("photo_version"),
                createdAt = Instant.ofEpochMilli(row.getLong("created_at")),
                updatedAt = Instant.ofEpochMilli(row.getLong("updated_at")),
            )
        }
    }
    return ids.mapNotNull { id ->
        items[id]?.copy(
            tags = tags[id].orEmpty().sortedWith(NaturalOrder),
            properties = properties[id].orEmpty(),
        )
    }
}

private fun Connection.saveTagsAndProperties(itemId: Long, draft: ItemDraft) {
    for (tag in draft.tags) {
        val normalized = normalizeForSearch(tag)
        execute("INSERT INTO tags (name, normalized) VALUES (?, ?) ON CONFLICT (normalized) DO NOTHING", tag, normalized)
        execute("INSERT OR IGNORE INTO item_tags (item_id, tag_id) SELECT ?, id FROM tags WHERE normalized = ?", itemId, normalized)
    }
    draft.properties.forEachIndexed { position, property ->
        execute(
            "INSERT INTO item_properties (item_id, position, name, value) VALUES (?, ?, ?, ?)",
            itemId, position, property.name, property.value,
        )
    }
}

private fun Connection.savePhoto(itemId: Long, photo: PhotoUpload, now: Long) {
    execute(
        """
        INSERT INTO item_photos (item_id, content_type, updated_at, thumbnail, image) VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (item_id) DO UPDATE SET
            content_type = excluded.content_type, updated_at = excluded.updated_at,
            thumbnail = excluded.thumbnail, image = excluded.image
        """,
        itemId, photo.contentType, now, photo.thumbnail, photo.image,
    )
}

private fun Connection.deleteUnusedTags() {
    execute("DELETE FROM tags WHERE id NOT IN (SELECT tag_id FROM item_tags)")
}
