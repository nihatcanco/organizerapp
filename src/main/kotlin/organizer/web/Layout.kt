package organizer.web

import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.formUrlEncode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.footer
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.img
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

suspend fun ApplicationCall.respondPage(
    title: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    search: SearchBox = SearchBox(),
    content: FlowContent.() -> Unit,
) = respondHtml(status) { page(title, search, content) }

suspend fun ApplicationCall.respondNotFound() = respondPage("Not found", HttpStatusCode.NotFound) {
    emptyState("Nothing here", "This page doesn't exist. It may have been deleted.") {
        a("/", classes = "btn") { +"Go home" }
    }
}

private fun HTML.page(title: String, search: SearchBox, content: FlowContent.() -> Unit) {
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
                    for ((name, value) in search.hidden) hiddenInput(name = name) { this.value = value }
                }
                nav("topnav") {
                    a("/locations", classes = "navlink") {
                        attributes["aria-label"] = "Locations"
                        icon("folder")
                        span { +"Locations" }
                    }
                    a("/tags", classes = "navlink") {
                        attributes["aria-label"] = "Tags"
                        icon("tag")
                        span { +"Tags" }
                    }
                    a("/items/new", classes = "btn btn-primary") {
                        icon("plus")
                        span { +"Add item" }
                    }
                }
            }
        }
        main("container") { content() }
        footer("site-footer") {
            div("container") {
                a("/export/items.csv") { icon("download"); +"Export CSV" }
                a("/export/backup") { icon("download"); +"Download backup" }
                a("/settings") { icon("settings"); +"Settings" }
            }
        }
    }
}

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

/** "Locations › Workshop › Cabinet" — the chain of places down to [locationId]. */
fun FlowContent.breadcrumbs(tree: LocationTree, locationId: Long?) {
    nav("breadcrumbs") {
        attributes["aria-label"] = "Breadcrumb"
        ol {
            li { a("/locations") { +"Locations" } }
            if (locationId != null) {
                for (location in tree.path(locationId)) li { a("/locations/${location.id}") { +location.name } }
            }
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
                        span("location-meta") { +locationSummary(tree, location.id) }
                    }
                    icon("chevron")
                }
            }
        }
    }
}

fun locationSummary(tree: LocationTree, id: Long): String {
    val parts = mutableListOf(plural(tree.totalItemCount(id), "item"))
    val children = tree.children(id).size
    if (children > 0) parts += plural(children, "sub-location")
    return parts.joinToString(" · ")
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
