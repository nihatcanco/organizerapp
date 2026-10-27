package organizer.web

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.formUrlEncode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormEncType
import kotlinx.html.FormMethod
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.checkBoxInput
import kotlinx.html.dataList
import kotlinx.html.dd
import kotlinx.html.dialog
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.fieldSet
import kotlinx.html.fileInput
import kotlinx.html.form
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.img
import kotlinx.html.label
import kotlinx.html.legend
import kotlinx.html.numberInput
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.textArea
import kotlinx.html.textInput
import kotlinx.html.th
import kotlinx.html.tr
import kotlinx.html.video
import organizer.model.Item
import organizer.model.ItemDraft
import organizer.model.ItemQuery
import organizer.model.LocationTree
import organizer.model.MAX_QUANTITY
import organizer.model.Photo
import organizer.model.PhotoChange
import organizer.model.PhotoUpload
import organizer.model.Property
import organizer.model.normalizeForSearch

// SVG is deliberately missing: it can carry scripts.
private val PHOTO_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/avif", "image/heic", "image/heif")

fun Route.itemRoutes(app: Services) {
    get("/i/{id}") { call.respondRedirect("/items/${call.id}") }

    get("/items/new") {
        val tree = app.locations.tree()
        val copyOf = call.queryParam("copy")?.toLongOrNull()?.let { app.items.get(it) }
        val location = call.queryParam("location")?.toLongOrNull()?.takeIf { it in tree }
        val added = call.queryParam("added")?.toLongOrNull()?.let { app.items.get(it) }
        val form = copyOf?.let(ItemForm::of) ?: ItemForm(locationId = location?.toString().orEmpty())
        call.respondItemForm(app, tree, form, item = null, added = added)
    }

    post("/items") {
        val submission = call.receiveSubmission()
        val tree = app.locations.tree()
        val form = ItemForm.of(submission.fields)
        val errors = linkedMapOf<String, String>()
        val photo = submission.photo(errors)
        val draft = form.validate(tree, errors)
            ?: return@post call.respondItemForm(app, tree, form, item = null, errors.withPhotoReminder(submission), HttpStatusCode.BadRequest)
        val id = app.items.create(draft, photo)
        if (submission.fields["next"] == "another") {
            val next = Parameters.build {
                draft.locationId?.let { append("location", it.toString()) }
                append("added", id.toString())
            }
            call.seeOther("/items/new?" + next.formUrlEncode())
        } else {
            call.seeOther("/items/$id")
        }
    }

    get("/items/{id}") {
        val item = app.items.get(call.id) ?: throw NotFoundException()
        val tree = app.locations.tree()
        call.respondPage(item.name) { itemPage(item, tree, call.request.uri) }
    }

    get("/items/{id}/edit") {
        val item = app.items.get(call.id) ?: throw NotFoundException()
        call.respondItemForm(app, app.locations.tree(), ItemForm.of(item), item)
    }

    post("/items/{id}") {
        val item = app.items.get(call.id) ?: throw NotFoundException()
        val submission = call.receiveSubmission()
        val tree = app.locations.tree()
        val form = ItemForm.of(submission.fields)
        val errors = linkedMapOf<String, String>()
        val photo = submission.photo(errors)
        val draft = form.validate(tree, errors)
            ?: return@post call.respondItemForm(app, tree, form, item, errors.withPhotoReminder(submission), HttpStatusCode.BadRequest)
        val photoChange = when {
            photo != null -> PhotoChange.Replace(photo)
            form.removePhoto -> PhotoChange.Remove
            else -> PhotoChange.Keep
        }
        app.items.update(item.id, draft, photoChange)
        call.seeOther("/items/${item.id}")
    }

    post("/items/{id}/delete") {
        val item = app.items.get(call.id) ?: throw NotFoundException()
        app.items.delete(item.id)
        call.seeOther(item.locationId?.let { "/locations/$it?deleted=item" } ?: "/?deleted=item")
    }

    post("/items/{id}/quantity") {
        val fields = call.receiveSubmission().fields
        val delta = fields["delta"].toIntOrNull()
        val set = fields["set"].toIntOrNull()
        val stock = when {
            delta != null -> app.items.adjustQuantity(call.id, delta.coerceIn(-MAX_QUANTITY, MAX_QUANTITY))
            set != null -> app.items.setQuantity(call.id, set)
            else -> throw BadRequestException("Enter a quantity.")
        } ?: throw NotFoundException()
        if (call.wantsJson) {
            call.respondText("""{"quantity":${stock.quantity},"low":${stock.isLow}}""", ContentType.Application.Json)
        } else {
            call.seeOther(safeReturnPath(fields["return"], "/items/${call.id}"))
        }
    }

    get("/items/{id}/photo") { call.respondPhoto(app.items.photo(call.id, thumbnail = false)) }

    get("/items/{id}/thumb") { call.respondPhoto(app.items.photo(call.id, thumbnail = true)) }
}

private suspend fun ApplicationCall.respondPhoto(photo: Photo?) {
    if (photo == null) throw NotFoundException()
    // Photo URLs include the photo's version, so they can be cached for good.
    response.header(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
    respondBytes(photo.bytes, ContentType.parse(photo.contentType))
}

/** The uploaded photo, if any. The browser normally sends a resized JPEG plus a thumbnail (see app.js). */
private fun Submission.photo(errors: MutableMap<String, String>): PhotoUpload? {
    val image = files["photo"] ?: return null
    val type = image.contentType?.withoutParameters()?.toString()?.lowercase()
    if (type == null || type !in PHOTO_TYPES) {
        errors["photo"] = "Choose a JPEG, PNG, WebP, GIF, AVIF or HEIC image."
        return null
    }
    val thumbnail = files["photo_thumb"]?.takeIf { it.contentType?.match(ContentType.Image.JPEG) == true }
    return PhotoUpload(type, image.bytes, thumbnail?.bytes)
}

// Browsers can't keep a chosen file when a form comes back with errors, so say so.
private fun Map<String, String>.withPhotoReminder(submission: Submission): Map<String, String> =
    if ("photo" in submission.files && "photo" !in this) this + ("photo" to "Please choose the photo again.") else this

data class ItemForm(
    val name: String = "",
    val quantity: String = "1",
    val minQuantity: String = "",
    val locationId: String = "",
    val tags: String = "",
    val notes: String = "",
    val properties: List<Property> = emptyList(),
    val removePhoto: Boolean = false,
) {
    /** Returns the item to save, or null after adding what's wrong to [errors]. */
    fun validate(tree: LocationTree, errors: MutableMap<String, String>): ItemDraft? {
        when {
            name.isEmpty() -> errors["name"] = "Give the item a name."
            name.length > 200 -> errors["name"] = "Keep the name under 200 characters."
        }
        val quantityValue = if (quantity.isEmpty()) 0 else quantity.toIntOrNull()?.takeIf { it in 0..MAX_QUANTITY }
        if (quantityValue == null) errors["quantity"] = "Enter a whole number, 0 or more."
        val minValue = minQuantity.toIntOrNull()?.takeIf { it in 0..MAX_QUANTITY }
        if (minQuantity.isNotEmpty() && minValue == null) errors["min_quantity"] = "Enter a whole number, or leave it empty."
        val location = locationId.toLongOrNull()
        if (locationId.isNotEmpty() && (location == null || location !in tree)) errors["location"] = "That location no longer exists."
        val tagList = parseTags(tags)
        when {
            tagList.any { it.length > 50 } -> errors["tags"] = "Each tag can be at most 50 characters."
            tagList.size > 30 -> errors["tags"] = "Use at most 30 tags."
        }
        when {
            properties.any { it.name.isEmpty() } -> errors["properties"] = "Every property needs a name, like “Package”."
            properties.size > 50 -> errors["properties"] = "Use at most 50 properties."
            properties.any { it.name.length > 100 || it.value.length > 1000 } -> errors["properties"] = "A property name or value is too long."
        }
        if (notes.length > 20_000) errors["notes"] = "Keep notes under 20,000 characters."
        if (errors.isNotEmpty() || quantityValue == null) return null
        return ItemDraft(name, location, quantityValue, minValue, notes, tagList, properties)
    }

    companion object {
        fun of(item: Item) = ItemForm(
            name = item.name,
            quantity = item.quantity.toString(),
            minQuantity = item.minQuantity?.toString().orEmpty(),
            locationId = item.locationId?.toString().orEmpty(),
            tags = item.tags.joinToString(", "),
            notes = item.notes,
            properties = item.properties,
        )

        fun of(fields: FormFields) = ItemForm(
            name = fields["name"],
            quantity = fields["quantity"],
            minQuantity = fields["min_quantity"],
            locationId = fields["location"],
            tags = fields["tags"],
            notes = fields["notes"],
            properties = fields.all("property_name").zip(fields.all("property_value")) { name, value -> Property(name.trim(), value.trim()) }
                .filterNot { it.name.isEmpty() && it.value.isEmpty() },
            removePhoto = fields.has("remove_photo"),
        )
    }
}

/** "resistor, #SMD, 0805" → [resistor, SMD, 0805], dropping duplicates that differ only in case or accents. */
fun parseTags(text: String): List<String> =
    text.split(',').map { it.trim().removePrefix("#").trim() }.filter { it.isNotEmpty() }.distinctBy(::normalizeForSearch)

private suspend fun ApplicationCall.respondItemForm(
    app: Services,
    tree: LocationTree,
    form: ItemForm,
    item: Item?,
    errors: Map<String, String> = emptyMap(),
    status: HttpStatusCode = HttpStatusCode.OK,
    added: Item? = null,
) {
    val tagSuggestions = app.items.tags().sortedByDescending { it.count }.take(20).map { it.name }
    val propertyNames = app.items.propertyNames()
    val ai = app.ai.enabled()
    val title = if (item == null) "Add item" else "Edit item"
    val location = form.locationId.toLongOrNull()
    val cancel = when {
        item != null -> "/items/${item.id}"
        location != null -> "/locations/$location"
        else -> "/"
    }
    respondPage(title, status) {
        if (added != null) notice("Added “${added.name}”.", "/items/${added.id}" to "View")
        pageHeader(title)
        form(
            if (item == null) "/items" else "/items/${item.id}",
            encType = FormEncType.multipartFormData,
            method = FormMethod.post,
            classes = "card form",
        ) {
            if (errors.isNotEmpty()) {
                div("form-errors") {
                    attributes["role"] = "alert"
                    icon("alert")
                    +"Please check the highlighted fields."
                }
            }
            field("Name", "name", errors) {
                textInput(name = "name") {
                    id = "name"
                    value = form.name
                    required = true
                    maxLength = "200"
                    autoFocus = item == null
                    placeholder = "e.g. 10 kΩ resistor"
                    attributes["autocomplete"] = "off"
                }
            }
            div("field-row") {
                field("Quantity", "quantity", errors) {
                    numberInput(name = "quantity") {
                        id = "quantity"
                        value = form.quantity
                        min = "0"
                        attributes["inputmode"] = "numeric"
                    }
                }
                field("Warn when at or below", "min_quantity", errors, hint = "Optional low-stock alert") {
                    numberInput(name = "min_quantity") {
                        id = "min_quantity"
                        value = form.minQuantity
                        min = "0"
                        attributes["inputmode"] = "numeric"
                    }
                }
            }
            field("Location", "location", errors) {
                locationSelect(tree, "location", location, noneLabel = "— Unsorted —")
            }
            field("Tags", "tags", errors, hint = "Separate with commas") {
                textInput(name = "tags") {
                    id = "tags"
                    value = form.tags
                    placeholder = "e.g. resistor, smd"
                    attributes["autocomplete"] = "off"
                    attributes["autocapitalize"] = "none"
                }
                if (tagSuggestions.isNotEmpty()) {
                    div("chips suggestions") {
                        for (tag in tagSuggestions) {
                            button(type = ButtonType.button, classes = "chip chip-button") {
                                attributes["data-add-tag"] = tag
                                +tag
                            }
                        }
                    }
                }
            }
            fieldSet(if ("properties" in errors) "field property-editor has-error" else "field property-editor") {
                legend { +"Properties" }
                p("hint") { +"Details such as value, package or voltage. They are searchable too." }
                div("property-rows") {
                    attributes["data-property-rows"] = ""
                    for (property in form.properties + Property("", "")) propertyRow(property)
                }
                button(type = ButtonType.button, classes = "btn btn-sm btn-ghost") {
                    attributes["data-add-property"] = ""
                    icon("plus")
                    +"Add property"
                }
                dataList {
                    id = "property-names"
                    for (name in propertyNames) option { value = name }
                }
                errors["properties"]?.let { p("field-error") { +it } }
            }
            field("Photo", "photo", errors) {
                if (item?.photoVersion != null) {
                    div("current-photo") {
                        img(alt = "Current photo", src = thumbUrl(item), classes = "thumb-lg")
                        label("checkbox") {
                            checkBoxInput(name = "remove_photo") { checked = form.removePhoto }
                            +"Remove photo"
                        }
                    }
                }
                img(alt = "", classes = "photo-preview") {
                    attributes["data-photo-preview"] = ""
                    attributes["hidden"] = ""
                }
                div("photo-picker") {
                    // Needs JavaScript and a camera, so app.js reveals it.
                    button(type = ButtonType.button, classes = "btn") {
                        attributes["data-take-photo"] = ""
                        attributes["hidden"] = ""
                        icon("camera")
                        +"Take photo"
                    }
                    fileInput(name = "photo") {
                        id = "photo"
                        accept = "image/*"
                        attributes["data-photo-input"] = ""
                    }
                }
                // Phones and tablets open their camera app for this one; app.js moves the photo into the input above.
                fileInput {
                    accept = "image/*"
                    attributes["capture"] = "environment"
                    attributes["tabindex"] = "-1"
                    attributes["hidden"] = ""
                    attributes["data-photo-capture"] = ""
                }
                cameraDialog()
                fileInput(name = "photo_thumb") {
                    accept = "image/jpeg"
                    attributes["tabindex"] = "-1"
                    attributes["hidden"] = ""
                    attributes["data-photo-thumb"] = ""
                }
                if (ai) {
                    // Needs JavaScript, so app.js reveals it.
                    div("ai-reader") {
                        attributes["hidden"] = ""
                        button(type = ButtonType.button, classes = "btn btn-sm") {
                            attributes["data-ai-fill"] = item?.takeIf { it.photoVersion != null }?.id?.toString().orEmpty()
                            icon("sparkles")
                            +"Fill in with AI"
                        }
                        p("hint") { +"Fills in the rest from the photo, the name, or both, with Claude by Anthropic." }
                        div("ai-status") {
                            attributes["data-ai-status"] = ""
                            attributes["role"] = "status"
                            attributes["hidden"] = ""
                        }
                    }
                } else {
                    p("hint") {
                        +"To fill in items from a photo or name with AI, add an Anthropic API key in "
                        a("/settings") { +"Settings" }
                        +"."
                    }
                }
            }
            field("Notes", "notes", errors, hint = "Datasheet links, where you bought it, …") {
                textArea(rows = "4") {
                    id = "notes"
                    name = "notes"
                    +form.notes
                }
            }
            div("form-actions") {
                button(type = ButtonType.submit, classes = "btn btn-primary") { +"Save" }
                if (item == null) {
                    button(type = ButtonType.submit, name = "next", classes = "btn") {
                        value = "another"
                        +"Save & add another"
                    }
                }
                a(cancel, classes = "btn btn-ghost") { +"Cancel" }
            }
        }
    }
}

/** The live camera view that app.js opens on computers with a webcam. */
private fun FlowContent.cameraDialog() {
    dialog("camera") {
        attributes["data-camera"] = ""
        attributes["aria-label"] = "Take photo"
        video("camera-view") {
            attributes["muted"] = ""
            attributes["playsinline"] = ""
        }
        p("camera-status") {
            attributes["data-camera-status"] = ""
            attributes["role"] = "status"
            attributes["hidden"] = ""
        }
        div("camera-actions") {
            button(type = ButtonType.button, classes = "btn btn-ghost") {
                attributes["data-camera-close"] = ""
                +"Cancel"
            }
            button(type = ButtonType.button, classes = "btn") {
                attributes["data-camera-switch"] = ""
                attributes["hidden"] = ""
                +"Switch camera"
            }
            button(type = ButtonType.button, classes = "btn btn-primary") {
                attributes["data-camera-shoot"] = ""
                disabled = true
                icon("camera")
                +"Take photo"
            }
        }
    }
}

private fun FlowContent.propertyRow(property: Property) {
    div("property-row") {
        textInput(name = "property_name") {
            value = property.name
            placeholder = "Name"
            attributes["list"] = "property-names"
            attributes["aria-label"] = "Property name"
            attributes["autocomplete"] = "off"
        }
        textInput(name = "property_value") {
            value = property.value
            placeholder = "Value"
            attributes["aria-label"] = "Property value"
            attributes["autocomplete"] = "off"
        }
        button(type = ButtonType.button, classes = "btn btn-icon btn-ghost") {
            attributes["data-remove-property"] = ""
            attributes["aria-label"] = "Remove property"
            icon("x")
        }
    }
}

private fun FlowContent.itemPage(item: Item, tree: LocationTree, here: String) {
    breadcrumbs(tree, item.locationId)
    pageHeader(item.name) {
        a("/items/${item.id}/edit", classes = "btn") { icon("edit"); span { +"Edit" } }
    }
    div(if (item.photoVersion != null) "item-layout has-photo" else "item-layout") {
        if (item.photoVersion != null) {
            a(photoUrl(item), classes = "photo") { img(alt = item.name, src = photoUrl(item)) }
        }
        div(if (item.isLowStock) "card facts low" else "card facts") {
            dl {
                dt { +"Quantity" }
                dd {
                    quantityControl(item, here, editable = true)
                    if (item.minQuantity != null) {
                        p("hint low-hint") {
                            if (item.isLowStock) icon("alert")
                            +"Warns at ${item.minQuantity} or fewer"
                        }
                    }
                }
                dt { +"Location" }
                dd {
                    if (item.locationId != null) {
                        a("/locations/${item.locationId}") { +tree.pathName(item.locationId) }
                    } else {
                        a(searchUrl(ItemQuery(unsorted = true))) { +"Unsorted" }
                    }
                }
                if (item.tags.isNotEmpty()) {
                    dt { +"Tags" }
                    dd { tagLinks(item.tags) }
                }
            }
        }
    }
    if (item.properties.isNotEmpty()) {
        section("card") {
            h2 { +"Properties" }
            table("properties") {
                tbody {
                    for (property in item.properties) {
                        tr {
                            th { +property.name }
                            td { linkified(property.value) }
                        }
                    }
                }
            }
        }
    }
    if (item.notes.isNotBlank()) {
        section("card") {
            h2 { +"Notes" }
            div("notes") { linkified(item.notes) }
        }
    }
    p("meta") { +"Updated ${relativeTime(item.updatedAt)} · Added ${formatDate(item.createdAt)}" }
    div("toolbar") {
        a("/items/new?copy=${item.id}", classes = "btn") { icon("copy"); +"Duplicate" }
        a("/labels?item=${item.id}", classes = "btn") { icon("qr"); +"Label" }
        form("/items/${item.id}/delete", method = FormMethod.post, classes = "inline-form") {
            attributes["data-confirm"] = "Delete “${item.name}”? This can't be undone."
            button(type = ButtonType.submit, classes = "btn btn-danger") { icon("trash"); +"Delete" }
        }
    }
}
