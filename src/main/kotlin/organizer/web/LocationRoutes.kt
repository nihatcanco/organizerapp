package organizer.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.request.uri
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.id
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.summary
import kotlinx.html.textArea
import kotlinx.html.textInput
import kotlinx.html.ul
import organizer.model.Item
import organizer.model.ItemQuery
import organizer.model.Location
import organizer.model.LocationDraft
import organizer.model.LocationTree
import organizer.model.NameRanges

private const val MAX_LOCATIONS_AT_ONCE = 500

fun Route.locationRoutes(app: Services) {
    // Short link used in QR codes: shorter URLs make codes that scan more easily.
    get("/l/{id}") { call.respondRedirect("/locations/${call.id}") }

    get("/locations") {
        val tree = app.locations.tree()
        val unsorted = app.items.stats().unsorted
        call.respondPage("Locations") {
            pageHeader("Locations") {
                a("/locations/new", classes = "btn btn-primary") { icon("folder-plus"); span { +"New location" } }
                if (tree.size > 0) a("/labels", classes = "btn btn-compact") { icon("qr"); span { +"Labels" } }
            }
            if (tree.size == 0) {
                emptyState("No locations yet", "Create a top-level place like “Workshop” or “Parts cabinet”, then add drawers inside it.") {
                    a("/locations/new", classes = "btn btn-primary") { icon("folder-plus"); +"Create a location" }
                }
            } else {
                div("card tree") { locationTree(tree, parentId = null, depth = 0) }
            }
            if (unsorted > 0) {
                p("muted") { a(searchUrl(ItemQuery(unsorted = true))) { +"${plural(unsorted, "item")} without a location" } }
            }
        }
    }

    get("/locations/new") {
        val tree = app.locations.tree()
        val parent = call.queryParam("parent")?.toLongOrNull()?.takeIf { it in tree }
        call.respondLocationForm(tree, LocationForm(parentId = parent?.toString().orEmpty()), editing = null)
    }

    post("/locations") {
        val tree = app.locations.tree()
        val form = LocationForm.of(call.receiveSubmission().fields)
        val errors = linkedMapOf<String, String>()
        val drafts = form.validate(tree, editing = null, errors)
            ?: return@post call.respondLocationForm(tree, form, editing = null, errors, HttpStatusCode.BadRequest)
        val ids = app.locations.create(drafts)
        if (ids.size == 1) {
            call.seeOther("/locations/${ids.single()}")
        } else {
            call.seeOther((drafts.first().parentId?.let { "/locations/$it" } ?: "/locations") + "?created=${ids.size}")
        }
    }

    get("/locations/{id}") {
        val tree = app.locations.tree()
        val location = tree[call.id] ?: throw NotFoundException()
        val items = app.items.inLocation(location.id)
        val notice = when {
            call.queryParam("created") != null -> "Created ${call.queryParam("created")} locations."
            call.queryParam("deleted") == "item" -> "Item deleted."
            call.queryParam("deleted") == "location" -> "Location deleted. Anything that was in it is now here."
            else -> null
        }
        call.respondPage(location.name, addItem = "/items/new?location=${location.id}") {
            locationPage(location, tree, items, call.request.uri, notice)
        }
    }

    get("/locations/{id}/edit") {
        val tree = app.locations.tree()
        val location = tree[call.id] ?: throw NotFoundException()
        call.respondLocationForm(tree, LocationForm.of(location), editing = location)
    }

    post("/locations/{id}") {
        val tree = app.locations.tree()
        val location = tree[call.id] ?: throw NotFoundException()
        val form = LocationForm.of(call.receiveSubmission().fields)
        val errors = linkedMapOf<String, String>()
        val draft = form.validate(tree, editing = location, errors)?.single()
            ?: return@post call.respondLocationForm(tree, form, location, errors, HttpStatusCode.BadRequest)
        app.locations.update(location.id, draft)
        call.seeOther("/locations/${location.id}")
    }

    post("/locations/{id}/delete") {
        val location = app.locations.get(call.id) ?: throw NotFoundException()
        app.locations.delete(location.id)
        call.seeOther(location.parentId?.let { "/locations/$it?deleted=location" } ?: "/locations")
    }
}

data class LocationForm(val name: String = "", val parentId: String = "", val description: String = "") {

    /** Returns the locations to save (several when the name has ranges), or null after adding to [errors]. */
    fun validate(tree: LocationTree, editing: Location?, errors: MutableMap<String, String>): List<LocationDraft>? {
        val parent = parentId.toLongOrNull()
        if (parentId.isNotEmpty() && (parent == null || parent !in tree)) {
            errors["parent"] = "That location no longer exists."
        } else if (editing != null && parent != null && tree.isInside(parent, editing.id)) {
            errors["parent"] = "A location can't go inside itself."
        }
        val count = if (editing == null) NameRanges.count(name) else 1
        when {
            name.isEmpty() -> errors["name"] = "Give the location a name."
            name.length > 200 -> errors["name"] = "Keep the name under 200 characters."
            count > MAX_LOCATIONS_AT_ONCE -> errors["name"] = "That would create $count locations; the most at once is $MAX_LOCATIONS_AT_ONCE."
        }
        if (description.length > 5000) errors["description"] = "Keep the description under 5000 characters."
        if (errors.isNotEmpty()) return null
        val names = if (editing == null) NameRanges.expand(name) else listOf(name)
        return names.map { LocationDraft(it, parent, description) }
    }

    companion object {
        fun of(location: Location) = LocationForm(location.name, location.parentId?.toString().orEmpty(), location.description)

        fun of(fields: FormFields) = LocationForm(fields["name"], fields["parent"], fields["description"])
    }
}

private suspend fun ApplicationCall.respondLocationForm(
    tree: LocationTree,
    form: LocationForm,
    editing: Location?,
    errors: Map<String, String> = emptyMap(),
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    val title = if (editing == null) "New location" else "Edit location"
    val parent = form.parentId.toLongOrNull()
    val cancel = when {
        editing != null -> "/locations/${editing.id}"
        parent != null -> "/locations/$parent"
        else -> "/locations"
    }
    respondPage(title, status) {
        breadcrumbs(tree, editing?.id ?: parent)
        pageHeader(title)
        form(if (editing == null) "/locations" else "/locations/${editing.id}", method = FormMethod.post, classes = "card form") {
            field(
                "Name", "name", errors,
                hint = if (editing == null) "Tip: create a set at once with ranges, like Drawer {1-12} or {A-D}{1-6}." else null,
            ) {
                textInput(name = "name") {
                    id = "name"
                    value = form.name
                    required = true
                    maxLength = "200"
                    autoFocus = editing == null
                    attributes["autocomplete"] = "off"
                }
            }
            field("Inside", "parent", errors) {
                locationSelect(tree, "parent", parent, noneLabel = "— Top level —", exclude = editing?.id)
            }
            field("Description", "description", errors, hint = "Optional") {
                textArea(rows = "3") {
                    id = "description"
                    name = "description"
                    +form.description
                }
            }
            div("form-actions") {
                button(type = ButtonType.submit, classes = "btn btn-primary") { +(if (editing == null) "Create" else "Save") }
                a(cancel, classes = "btn btn-ghost") { +"Cancel" }
            }
        }
    }
}

private fun FlowContent.locationPage(location: Location, tree: LocationTree, items: List<Item>, here: String, notice: String?) {
    breadcrumbs(tree, location.parentId)
    pageHeader(location.name) {
        a("/labels?location=${location.id}", classes = "btn btn-compact") { icon("qr"); span { +"Labels" } }
        a("/locations/${location.id}/edit", classes = "btn btn-compact") { icon("edit"); span { +"Edit" } }
    }
    if (location.description.isNotBlank()) div("description") { linkified(location.description) }
    if (notice != null) notice(notice)
    div("toolbar") {
        a("/items/new?location=${location.id}", classes = "btn btn-primary") { icon("plus"); +"Add item" }
        a("/locations/new?parent=${location.id}", classes = "btn") { icon("folder-plus"); +"Add sub-location" }
    }
    val children = tree.children(location.id)
    if (children.isNotEmpty()) {
        section {
            sectionHeader("Inside ${location.name}")
            locationList(children, tree)
        }
    }
    // A cabinet that only holds drawers doesn't need an empty "Items" section.
    if (items.isNotEmpty() || children.isEmpty()) {
        section {
            sectionHeader(if (items.isEmpty()) "Items" else "Items (${items.size})")
            if (items.isEmpty()) {
                p("muted") { +"Nothing here yet." }
            } else {
                itemList(items, tree, here, showLocation = false)
            }
        }
    }
    deleteLocationForm(location, tree)
}

private fun FlowContent.deleteLocationForm(location: Location, tree: LocationTree) {
    val items = tree.directItemCount(location.id)
    val children = tree.children(location.id).size
    val parent = location.parentId?.let(tree::get)
    val consequences = buildList {
        if (parent != null) {
            val moving = listOfNotNull(
                items.takeIf { it > 0 }?.let { plural(it, "item") },
                children.takeIf { it > 0 }?.let { plural(it, "sub-location") },
            )
            if (moving.isNotEmpty()) add("its ${moving.joinToString(" and ")} will move to “${parent.name}”")
        } else {
            if (items > 0) add("its ${plural(items, "item")} will become unsorted")
            if (children > 0) add("its ${plural(children, "sub-location")} will move to the top level")
        }
    }
    val consequence = consequences.joinToString(" and ")
    form("/locations/${location.id}/delete", method = FormMethod.post, classes = "danger-zone") {
        attributes["data-confirm"] = "Delete “${location.name}”?" +
            if (consequence.isEmpty()) "" else " " + consequence.replaceFirstChar { it.uppercase() } + "."
        button(type = ButtonType.submit, classes = "btn btn-danger") { icon("trash"); +"Delete location" }
        if (consequence.isNotEmpty()) p("hint") { +"Nothing is lost: $consequence." }
    }
}

private fun FlowContent.locationTree(tree: LocationTree, parentId: Long?, depth: Int) {
    ul("tree-list") {
        for (location in tree.children(parentId)) {
            li {
                val link: FlowContent.() -> Unit = {
                    a("/locations/${location.id}", classes = "tree-link") {
                        icon(if (tree.children(location.id).isEmpty()) "drawer" else "cabinet")
                        span("tree-name") { +location.name }
                        val count = tree.totalItemCount(location.id)
                        if (count > 0) span("count") { +count.toString() }
                    }
                }
                if (tree.children(location.id).isEmpty()) {
                    link()
                } else {
                    details {
                        if (depth == 0) attributes["open"] = ""
                        summary { link() }
                        locationTree(tree, location.id, depth + 1)
                    }
                }
            }
        }
    }
}
