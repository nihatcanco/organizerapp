package organizer.model

/**
 * Every location, held in memory for building paths, menus and item counts.
 * Even a well-stocked workshop rarely has more than a few hundred locations.
 */
class LocationTree(locations: List<Location>, private val itemCounts: Map<Long, Int> = emptyMap()) {
    private val byId = locations.associateBy { it.id }
    private val childrenOf: Map<Long?, List<Location>> = locations
        .groupBy { it.parentId }
        .mapValues { (_, children) -> children.sortedWith(compareBy(NaturalOrder) { it.name }) }

    val size: Int get() = byId.size
    val roots: List<Location> get() = children(null)

    operator fun get(id: Long): Location? = byId[id]

    operator fun contains(id: Long): Boolean = id in byId

    fun children(id: Long?): List<Location> = childrenOf[id].orEmpty()

    /** The location and its ancestors, outermost first. */
    fun path(id: Long): List<Location> {
        val path = ArrayList<Location>()
        var current = byId[id]
        // The size check guards against looping forever should the data ever contain a cycle.
        while (current != null && path.size <= byId.size) {
            path += current
            current = current.parentId?.let(byId::get)
        }
        return path.asReversed()
    }

    fun pathName(id: Long): String = path(id).joinToString(" / ") { it.name }

    /** Just the innermost two levels, e.g. "Parts cabinet / B1": the end of a path says where to look. */
    fun shortPathName(id: Long): String = path(id).takeLast(2).joinToString(" / ") { it.name }

    /** True when [id] is [ancestorId] or somewhere inside it. */
    fun isInside(id: Long, ancestorId: Long): Boolean = path(id).any { it.id == ancestorId }

    /** [id] followed by everything nested inside it, in tree order. */
    fun subtree(id: Long): List<Location> = byId[id]?.let { listOf(it) + descendants(id) }.orEmpty()

    fun descendants(id: Long): List<Location> = children(id).flatMap { listOf(it) + descendants(it.id) }

    /** Every location in tree order, for menus and label sheets. */
    fun all(): List<Location> = roots.flatMap { subtree(it.id) }

    fun directItemCount(id: Long): Int = itemCounts[id] ?: 0

    fun totalItemCount(id: Long): Int = directItemCount(id) + children(id).sumOf { totalItemCount(it.id) }

    fun search(query: String): List<Location> {
        val terms = searchTerms(query)
        if (terms.isEmpty()) return emptyList()
        return all().filter { location ->
            val text = normalizeForSearch(location.name + "\n" + location.description)
            terms.all { it in text }
        }
    }
}
