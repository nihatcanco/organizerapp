package organizer.model

import java.time.Instant

const val MAX_QUANTITY = 1_000_000_000

data class Location(
    val id: Long,
    val parentId: Long?,
    val name: String,
    val description: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class LocationDraft(val name: String, val parentId: Long?, val description: String = "")

/** A free-form detail such as "Package: 0805" or "Voltage: 16 V". */
data class Property(val name: String, val value: String)

data class Item(
    val id: Long,
    val locationId: Long?,
    val name: String,
    val quantity: Int,
    val minQuantity: Int?,
    val notes: String,
    val tags: List<String>,
    val properties: List<Property>,
    /** Changes whenever the photo does (used to bust caches); null when there is no photo. */
    val photoVersion: Long?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val isLowStock: Boolean get() = minQuantity != null && quantity <= minQuantity
}

data class ItemDraft(
    val name: String,
    val locationId: Long?,
    val quantity: Int = 1,
    val minQuantity: Int? = null,
    val notes: String = "",
    val tags: List<String> = emptyList(),
    val properties: List<Property> = emptyList(),
)

data class Stock(val quantity: Int, val isLow: Boolean)

class Photo(val contentType: String, val bytes: ByteArray, val version: Long)

/** [thumbnail] is a small JPEG made by the browser; it is missing when the upload came without JavaScript. */
class PhotoUpload(val contentType: String, val image: ByteArray, val thumbnail: ByteArray?)

sealed interface PhotoChange {
    data object Keep : PhotoChange
    data object Remove : PhotoChange
    class Replace(val upload: PhotoUpload) : PhotoChange
}

data class ItemQuery(
    val text: String = "",
    val tag: String? = null,
    val lowStock: Boolean = false,
    val unsorted: Boolean = false,
) {
    val isEmpty: Boolean get() = text.isBlank() && tag == null && !lowStock && !unsorted
}

class SearchPage(val items: List<Item>, val total: Int)

data class TagCount(val name: String, val count: Int)

data class Stats(val items: Int, val locations: Int, val lowStock: Int, val unsorted: Int)

/** What one question to Claude used, and its price in US dollars at Anthropic's list prices. */
data class AiUsage(val inputTokens: Long, val outputTokens: Long, val webSearches: Long, val dollars: Double)

/** Questions to Claude added up; [since] is when the first of them was asked. */
data class AiSpending(val requests: Int, val webSearches: Long, val dollars: Double, val since: Instant?)
