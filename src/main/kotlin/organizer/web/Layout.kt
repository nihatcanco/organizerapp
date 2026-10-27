package organizer.web

import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.formUrlEncode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.request.path
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.img
import kotlinx.html.kbd
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.numberInput
import kotlinx.html.ol
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.searchInput
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.title
import kotlinx.html.ul
import organizer.APP_NAME
import organizer.model.Item
import organizer.model.ItemQuery
import organizer.model.Location
import organizer.model.LocationTree

/** What the search box in the header shows; on the search page it also carries the active filters. */
class SearchBox(
    val query: String = "",
    val hidden: Map<String, String> = emptyMap(),
    val autofocus: Boolean = false,
    val live: Boolean = false,
)

/** A page in the app's frame; [addItem] is where the header's "Add item" goes, such as a form already set to this location. */
suspend fun ApplicationCall.respondPage(
    title: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    search: SearchBox = SearchBox(),
    addItem: String = "/items/new",
    content: FlowContent.() -> Unit,
) = respondHtml(status) { page(title, request.path(), search, addItem, content) }

suspend fun ApplicationCall.respondNotFound() = respondPage("Not found", HttpStatusCode.NotFound) {
    emptyState("Nothing here", "This page doesn't exist. It may have been deleted.") {
        a("/", classes = "btn") { +"Go home" }
    }
}

private fun HTML.page(title: String, path: String, search: SearchBox, addItem: String, content: FlowContent.() -> Unit) {
    attributes["lang"] = "en"
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1, viewport-fit=cover")
        meta(name = "color-scheme", content = "light dark")
        title(if (title == APP_NAME) APP_NAME else "$title · $APP_NAME")
        link(rel = "icon", type = "image/svg+xml", href = Assets.url("icon.svg"))
        link(rel = "stylesheet", href = Assets.url("app.css"))
        script(src = Assets.url("app.js")) { defer = true }
    }
    body {
        header("topbar") {
            div("topbar-inner") {
                a("/", classes = "brand") {
                    icon("cabinet")
                    span { +APP_NAME }
                }
                form("/search", method = FormMethod.get, classes = "searchbar") {
                    attributes["role"] = "search"
                    if (search.live) attributes["data-live-search"] = ""
                    icon("search")
                    searchInput(name = "q") {
                        id = "q"
                        value = search.query
                        placeholder = "Search items, tags, properties…"
                        attributes["aria-label"] = "Search"
                        attributes["autocomplete"] = "off"
                        attributes["enterkeyhint"] = "search"
                        if (search.autofocus) autoFocus = true
                    }
                    // app.js focuses the search box when / is pressed.
                    kbd("search-key") {
                        attributes["aria-hidden"] = "true"
                        attributes["title"] = "Press / to search"
                        +"/"
                    }
                    for ((name, value) in search.hidden) hiddenInput(name = name) { this.value = value }
                }
                nav("topnav") {
                    navLink("/locations", "Locations", "folder", current = path.startsWith("/locations") || path == "/labels")
                    navLink("/tags", "Tags", "tag", current = path == "/tags")
                    navLink("/settings", "Settings", "settings", current = path.startsWith("/settings"))
                    a(addItem, classes = "btn btn-primary") {
                        icon("plus")
                        span { +"Add item" }
                    }
                }
            }
        }
        main("container") { content() }
    }
}

private fun FlowContent.navLink(href: String, label: String, icon: String, current: Boolean) {
    a(href, classes = "navlink") {
        attributes["aria-label"] = label
        if (current) attributes["aria-current"] = "page"
        icon(icon)
        span { +label }
    }
}

/** The page's title and buttons. Buttons with the btn-compact class show only their icon on phones. */
fun FlowContent.pageHeader(title: String, actions: (DIV.() -> Unit)? = null) {
    div("page-header") {
        h1 { +title }
        if (actions != null) div("page-actions") { actions() }
    }
}

fun FlowContent.sectionHeader(title: String, actions: (DIV.() -> Unit)? = null) {
    div("section-header") {
        h2 { +title }
        if (actions != null) div("section-actions") { actions() }
    }
}

/** "Locations › Workshop › Cabinet" — the chain of places down to [locationId], then [last] (link to text) if given. */
fun FlowContent.breadcrumbs(tree: LocationTree, locationId: Long?, last: Pair<String, String>? = null) {
    nav("breadcrumbs") {
        attributes["aria-label"] = "Breadcrumb"
        ol {
            li { a("/locations") { +"Locations" } }
            if (locationId != null) {
                for (location in tree.path(locationId)) li { a("/locations/${location.id}") { +location.name } }
            }
            if (last != null) li { a(last.first) { +last.second } }
        }
    }
}

fun FlowContent.emptyState(title: String, text: String, actions: (DIV.() -> Unit)? = null) {
    div("empty") {
        icon("box")
        h2 { +title }
        p { +text }
        if (actions != null) div("empty-actions") { actions() }
    }
}

fun FlowContent.notice(text: String, link: Pair<String, String>? = null) {
    div("notice") {
        attributes["role"] = "status"
        icon("check")
        span {
            +text
            if (link != null) {
                +" "
                a(link.first) { +link.second }
            }
        }
    }
}

fun FlowContent.itemList(items: List<Item>, tree: LocationTree, returnTo: String, showLocation: Boolean = true) {
    ul("item-list") {
        for (item in items) {
            li(if (item.isLowStock) "item-row low" else "item-row") {
                a("/items/${item.id}", classes = "item-link") {
                    if (item.photoVersion != null) {
                        img(alt = "", src = thumbUrl(item), classes = "thumb") { attributes["loading"] = "lazy" }
                    } else {
                        span("thumb thumb-empty") { icon("box") }
                    }
                    span("item-text") {
                        span("item-name") { +item.name }
                        val details = item.properties.map { it.value }.filter { it.isNotBlank() }.take(3)
                        if (details.isNotEmpty()) span("item-details") { +details.joinToString(" · ") }
                        if (showLocation) {
                            span("item-location") {
                                if (item.locationId != null) {
                                    attributes["title"] = tree.pathName(item.locationId)
                                    icon("drawer")
                                    +tree.shortPathName(item.locationId)
                                } else {
                                    +"Unsorted"
                                }
                            }
                        }
                        if (item.tags.isNotEmpty()) span("chips") { for (tag in item.tags) span("chip") { +tag } }
                    }
                }
                quantityControl(item, returnTo)
            }
        }
    }
}

/**
 * − / + buttons around the quantity. Works as a plain form; app.js turns it into instant updates.
 * The [editable] version (item page) also lets you type an exact count.
 */
fun FlowContent.quantityControl(item: Item, returnTo: String, editable: Boolean = false) {
    form("/items/${item.id}/quantity", method = FormMethod.post, classes = if (editable) "stepper stepper-lg" else "stepper") {
        attributes["data-quantity"] = ""
        if (item.minQuantity != null) attributes["data-min"] = item.minQuantity.toString()
        hiddenInput(name = "return") { value = returnTo }
        if (editable) {
            // Pressing Enter submits with the first button, so that one has to mean "set to the typed number".
            button(type = ButtonType.submit, name = "op", classes = "sr-only") {
                value = "set"
                attributes["tabindex"] = "-1"
                attributes["aria-hidden"] = "true"
                +"Set"
            }
        }
        button(type = ButtonType.submit, name = "delta", classes = "step") {
            value = "-1"
            attributes["aria-label"] = "One less"
            icon("minus")
        }
        if (editable) {
            numberInput(name = "set", classes = "qty") {
                value = item.quantity.toString()
                min = "0"
                attributes["inputmode"] = "numeric"
                attributes["aria-label"] = "Quantity"
            }
        } else {
            span("qty") {
                attributes["data-qty"] = ""
                attributes["aria-label"] = "Quantity"
                +item.quantity.toString()
            }
        }
        button(type = ButtonType.submit, name = "delta", classes = "step") {
            value = "1"
            attributes["aria-label"] = "One more"
            icon("plus")
        }
    }
}

fun FlowContent.locationList(locations: List<Location>, tree: LocationTree, showPath: Boolean = false) {
    ul("location-list") {
        for (location in locations) {
            li {
                a("/locations/${location.id}", classes = "location-card") {
                    icon(if (tree.children(location.id).isEmpty()) "drawer" else "cabinet")
                    span("location-text") {
                        span("location-name") { +location.name }
                        if (showPath && location.parentId != null) {
                            span("location-path") { +tree.pathName(location.parentId) }
                        }
                        span("location-meta") {
                            // Each part stays on one line, so "2 sub-locations" never breaks at its hyphen.
                            locationSummary(tree, location.id).forEachIndexed { index, part ->
                                if (index > 0) +" · "
                                span { +part }
                            }
                        }
                    }
                    icon("chevron")
                }
            }
        }
    }
}

/** "3 items", "12 sub-locations", both, or "Empty". */
fun locationSummary(tree: LocationTree, id: Long): List<String> {
    val items = tree.totalItemCount(id)
    val children = tree.children(id).size
    return buildList {
        if (items > 0) add(plural(items, "item"))
        if (children > 0) add(plural(children, "sub-location"))
        if (isEmpty()) add("Empty")
    }
}

/** A labelled form field; [name] is also the input's id, and where its error message is looked up. */
fun FlowContent.field(
    label: String,
    name: String,
    errors: Map<String, String>,
    hint: String? = null,
    input: DIV.() -> Unit,
) {
    val error = errors[name]
    div(if (error != null) "field has-error" else "field") {
        label {
            htmlFor = name
            +label
        }
        input()
        when {
            error != null -> p("field-error") { +error }
            hint != null -> p("hint") { +hint }
        }
    }
}

fun FlowContent.locationSelect(tree: LocationTree, name: String, selected: Long?, noneLabel: String, exclude: Long? = null) {
    select {
        id = name
        this.name = name
        option {
            value = ""
            if (selected == null) this.selected = true
            +noneLabel
        }
        for (location in tree.all()) {
            if (exclude != null && tree.isInside(location.id, exclude)) continue
            option {
                value = location.id.toString()
                if (location.id == selected) this.selected = true
                +tree.pathName(location.id)
            }
        }
    }
}

fun FlowContent.tagLinks(tags: List<String>) {
    span("chips") {
        for (tag in tags) a(searchUrl(ItemQuery(tag = tag)), classes = "chip chip-link") { +tag }
    }
}

fun searchUrl(query: ItemQuery, limit: Int? = null): String {
    val parameters = Parameters.build {
        if (query.text.isNotBlank()) append("q", query.text)
        if (query.tag != null) append("tag", query.tag)
        if (query.lowStock) append("low", "1")
        if (query.unsorted) append("unsorted", "1")
        if (limit != null) append("limit", limit.toString())
    }
    return if (parameters.isEmpty()) "/search" else "/search?" + parameters.formUrlEncode()
}

fun photoUrl(item: Item) = "/items/${item.id}/photo?v=${item.photoVersion}"

fun thumbUrl(item: Item) = "/items/${item.id}/thumb?v=${item.photoVersion}"
