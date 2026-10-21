package organizer.store

import kotlinx.coroutines.runBlocking
import organizer.db.Database
import organizer.model.ItemDraft
import organizer.model.ItemQuery
import organizer.model.LocationDraft
import organizer.model.NameRanges
import organizer.model.PhotoChange
import organizer.model.PhotoUpload
import organizer.model.Property
import organizer.model.Stock
import organizer.model.TagCount
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepositoryTest {
    private val dir = createTempDirectory("organizer-test")
    private val db = Database.open(dir.resolve("test.db"))
    private val locations = LocationRepository(db)
    private val items = ItemRepository(db)

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `locations nest, sort naturally and know their paths`() = runBlocking {
        val workshop = locations.create(LocationDraft("Workshop", null))
        val cabinet = locations.create(LocationDraft("Cabinet", workshop))
        val drawers = locations.create(NameRanges.expand("Drawer {1-10}").map { LocationDraft(it, cabinet) })
        items.create(ItemDraft("Fuse", drawers.first()))
        items.create(ItemDraft("Diode", drawers.last()))

        val tree = locations.tree()
        assertEquals("Workshop / Cabinet / Drawer 10", tree.pathName(drawers.last()))
        assertEquals((1..10).map { "Drawer $it" }, tree.children(cabinet).map { it.name })
        assertEquals(2, tree.totalItemCount(workshop))
        assertTrue(tree.isInside(drawers.first(), workshop))
    }

    @Test
    fun `a location cannot be moved inside itself`(): Unit = runBlocking {
        val a = locations.create(LocationDraft("A", null))
        val b = locations.create(LocationDraft("B", a))
        assertFailsWith<IllegalArgumentException> { locations.update(a, LocationDraft("A", b)) }
        assertFailsWith<IllegalArgumentException> { locations.update(a, LocationDraft("A", a)) }
    }

    @Test
    fun `deleting a location moves its contents to the parent`() = runBlocking {
        val cabinet = locations.create(LocationDraft("Cabinet", null))
        val drawer = locations.create(LocationDraft("Drawer", cabinet))
        val box = locations.create(LocationDraft("Box", drawer))
        val item = items.create(ItemDraft("Resistor", drawer))

        assertTrue(locations.delete(drawer))
        assertEquals(cabinet, items.get(item)?.locationId)
        assertEquals(cabinet, locations.get(box)?.parentId)
    }

    @Test
    fun `items keep their tags and properties, and unused tags disappear`() = runBlocking {
        val draft = ItemDraft(
            name = "LM317T",
            locationId = null,
            quantity = 5,
            tags = listOf("regulator", "TO-220"),
            properties = listOf(Property("Output", "1.25–37 V"), Property("Package", "TO-220")),
        )
        val id = items.create(draft)
        val item = items.get(id)!!
        assertEquals(listOf("regulator", "TO-220"), item.tags)
        assertEquals(draft.properties, item.properties)

        items.update(id, draft.copy(tags = listOf("Regulator", "linear")))
        assertEquals(listOf(TagCount("linear", 1), TagCount("regulator", 1)), items.tags())
    }

    @Test
    fun `search matches parts of words, properties, tags and accents`() = runBlocking {
        val regulator = items.create(ItemDraft("LM317T regulator", null, tags = listOf("power")))
        val resistor = items.create(ItemDraft("Direnç 10k", null, properties = listOf(Property("Package", "0805"))))

        suspend fun ids(text: String, tag: String? = null) = items.search(ItemQuery(text, tag), 10).items.map { it.id }
        assertEquals(listOf(regulator), ids("317"))
        assertEquals(listOf(resistor), ids("direnc 0805"))
        assertEquals(listOf(resistor), ids("DİRENÇ"))
        assertEquals(listOf(regulator), ids("", tag = "Power"))
        assertEquals(emptyList<Long>(), ids("317 0805"))
        assertEquals(2, items.search(ItemQuery(), 1).total)
    }

    @Test
    fun `quantity never goes below zero and reports low stock`() = runBlocking {
        val id = items.create(ItemDraft("Fuse", null, quantity = 2, minQuantity = 1))
        assertEquals(Stock(1, isLow = true), items.adjustQuantity(id, -1))
        assertEquals(Stock(0, isLow = true), items.adjustQuantity(id, -5))
        assertEquals(Stock(10, isLow = false), items.setQuantity(id, 10))
        assertNull(items.adjustQuantity(id + 1, 1))
        assertEquals(0, items.stats().lowStock)
    }

    @Test
    fun `photos are stored, served as thumbnails and removed`() = runBlocking {
        val image = byteArrayOf(1, 2, 3)
        val thumbnail = byteArrayOf(4, 5)
        val id = items.create(ItemDraft("Relay", null), PhotoUpload("image/png", image, thumbnail))

        assertContentEquals(image, items.photo(id, thumbnail = false)?.bytes)
        assertEquals("image/jpeg", items.photo(id, thumbnail = true)?.contentType)
        assertContentEquals(thumbnail, items.photo(id, thumbnail = true)?.bytes)

        items.update(id, ItemDraft("Relay", null), PhotoChange.Remove)
        assertNull(items.photo(id, thumbnail = false))
        assertNull(items.get(id)?.photoVersion)
    }

    @Test
    fun `ids are never reused, so printed labels can't point at something new`() = runBlocking {
        val drawer = locations.create(LocationDraft("Drawer", null))
        val item = items.create(ItemDraft("Fuse", drawer))
        items.delete(item)
        locations.delete(drawer)
        assertTrue(locations.create(LocationDraft("Bin", null)) > drawer)
        assertTrue(items.create(ItemDraft("Diode", null)) > item)
    }

    @Test
    fun `backup writes a complete, usable copy`() = runBlocking {
        items.create(ItemDraft("Capacitor", null))
        val target = dir.resolve("backup.db")
        db.backupTo(target)
        assertEquals(listOf("Capacitor"), ItemRepository(Database.open(target)).all().map { it.name })
    }
}
