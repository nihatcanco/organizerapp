package organizer.web

import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import organizer.model.ItemQuery
import organizer.model.Location
import organizer.model.LocationTree
import organizer.model.SearchPage

private const val PAGE_SIZE = 100

fun Route.searchRoutes(app: Services) {
    get("/search") {
        val query = ItemQuery(
            text = call.queryParam("q").orEmpty(),
            tag = call.queryParam("tag"),
            lowStock = call.queryParam("low") == "1",
            unsorted = call.queryParam("unsorted") == "1",
        )
        val limit = call.queryParam("limit")?.toIntOrNull()?.coerceIn(PAGE_SIZE, 10_000) ?: PAGE_SIZE
        val tree = app.locations.tree()
        val page = app.items.search(query, limit)
        val locations = tree.search(query.text)
        val filters = buildMap {
            query.tag?.let { put("tag", it) }
            if (query.lowStock) put("low", "1")
            if (query.unsorted) put("unsorted", "1")
        }
        // The search box is "live": app.js reloads this page as you type and swaps in #results.
        call.respondPage(title(query), search = SearchBox(query.text, filters, autofocus = query.isEmpty, live = true)) {
            searchResults(query, page, locations, tree, limit)
        }
    }

    get("/tags") {
        val tags = app.items.tags()
        call.respondPage("Tags") {
            pageHeader("Tags")
            if (tags.isEmpty()) {
                emptyState("No tags yet", "Give items tags such as “resistor” or “smd”, then find everything with a tag here.")
            } else {
                div("card tag-cloud") {
                    for (tag in tags) {
                        a(searchUrl(ItemQuery(tag = tag.name)), classes = "chip chip-link chip-lg") {
                            +tag.name
                            span("count") { +tag.count.toString() }
                        }
                    }
                }
            }
        }
    }
}

private fun title(query: ItemQuery) = when {
    query.text.isNotBlank() -> "Results for “${query.text}”"
    query.tag != null -> "Tagged “${query.tag}”"
    query.lowStock -> "Running low"
    query.unsorted -> "Unsorted items"
    else -> "All items"
}

private fun FlowContent.searchResults(query: ItemQuery, page: SearchPage, locations: List<Location>, tree: LocationTree, limit: Int) {
    div {
        id = "results"
        pageHeader(title(query))
        div("filters") {
            if (query.tag != null) {
                a(searchUrl(query.copy(tag = null)), classes = "chip chip-active") {
                    attributes["aria-label"] = "Remove tag filter ${query.tag}"
                    icon("tag")
                    +query.tag
                    icon("x")
                }
            }
            filterToggle("Running low", query.lowStock, searchUrl(query.copy(lowStock = !query.lowStock)))
            filterToggle("Unsorted", query.unsorted, searchUrl(query.copy(unsorted = !query.unsorted)))
        }
        if (locations.isNotEmpty()) {
            section {
                sectionHeader("Locations")
                locationList(locations, tree, showPath = true)
            }
        }
        section {
            sectionHeader(plural(page.total, "item"))
            if (page.items.isEmpty()) {
                p("muted") { +(if (query.isEmpty) "No items yet." else "No items match.") }
            } else {
                itemList(page.items, tree, returnTo = searchUrl(query))
            }
            if (page.total > page.items.size) {
                div("more") {
                    a(searchUrl(query, limit + PAGE_SIZE), classes = "btn") { +"Show more (${page.total - page.items.size} left)" }
                }
            }
        }
    }
}

private fun FlowContent.filterToggle(label: String, active: Boolean, href: String) {
    a(href, classes = if (active) "chip chip-active" else "chip chip-link") {
        +label
        if (active) icon("x")
    }
}
