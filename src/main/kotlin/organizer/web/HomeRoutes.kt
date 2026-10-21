package organizer.web

import io.ktor.server.request.uri
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.li
import kotlinx.html.ol
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.strong
import organizer.APP_NAME
import organizer.model.ItemQuery

fun Route.homeRoutes(app: Services) {
    get("/") {
        val stats = app.items.stats()
        if (stats.items == 0 && stats.locations == 0) {
            return@get call.respondPage(APP_NAME) { welcome() }
        }
        val tree = app.locations.tree()
        val lowStock = app.items.search(ItemQuery(lowStock = true), limit = 5)
        val recent = app.items.recentlyUpdated(limit = 8)
        val here = call.request.uri
        call.respondPage(APP_NAME) {
            if (call.queryParam("deleted") == "item") notice("Item deleted.")
            div("stats") {
                stat(stats.items, "items", "/search")
                stat(stats.locations, "locations", "/locations")
                stat(stats.lowStock, "running low", searchUrl(ItemQuery(lowStock = true)), warn = stats.lowStock > 0)
            }
            section {
                sectionHeader("Locations") {
                    a("/locations/new", classes = "btn btn-sm") { icon("folder-plus"); span { +"New" } }
                }
                if (tree.roots.isEmpty()) p("muted") { +"No locations yet." } else locationList(tree.roots, tree)
                if (stats.unsorted > 0) {
                    p("muted") { a(searchUrl(ItemQuery(unsorted = true))) { +"${plural(stats.unsorted, "item")} without a location" } }
                }
            }
            if (lowStock.items.isNotEmpty()) {
                section {
                    sectionHeader("Running low") {
                        if (lowStock.total > lowStock.items.size) {
                            a(searchUrl(ItemQuery(lowStock = true))) { +"See all ${lowStock.total}" }
                        }
                    }
                    itemList(lowStock.items, tree, here)
                }
            }
            if (recent.isNotEmpty()) {
                section {
                    sectionHeader("Recently updated") { a("/search") { +"All items" } }
                    itemList(recent, tree, here)
                }
            }
        }
    }
}

private fun FlowContent.stat(value: Int, label: String, href: String, warn: Boolean = false) {
    a(href, classes = if (warn) "stat stat-warn" else "stat") {
        strong { +value.toString() }
        span { +label }
    }
}

private fun FlowContent.welcome() {
    div("welcome") {
        icon("cabinet")
        h1 { +"Welcome to your organizer" }
        p("lead") { +"Start with the places where things live, then put items in them." }
        ol("steps") {
            li {
                strong { +"Create a location" }
                +", like “Workshop” or “Parts cabinet”."
            }
            li {
                strong { +"Add sub-locations" }
                +" inside it, such as its drawers. Create a whole set at once with a range: “Drawer {1-24}”."
            }
            li {
                strong { +"Add items" }
                +" with quantities, tags and properties — then print QR labels for your drawers and scan them with your phone."
            }
        }
        div("empty-actions") {
            a("/locations/new", classes = "btn btn-primary") { icon("folder-plus"); +"Create a location" }
            a("/items/new", classes = "btn") { icon("plus"); +"Add an item" }
        }
    }
}
