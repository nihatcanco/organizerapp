package organizer.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.ButtonType
import kotlinx.html.FormMethod
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.checkBoxInput
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.p
import kotlinx.html.passwordInput
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.strong
import organizer.ai.Ai
import organizer.ai.AiException
import organizer.ai.AnthropicClaude

fun Route.settingsRoutes(app: Services) {
    get("/settings") { call.respondSettings(app) }

    post("/settings/api-key") {
        val key = call.receiveSubmission().fields["api_key"]
        if (key.isEmpty()) return@post call.respondSettings(app, mapOf("api_key" to "Paste the API key first."))
        try {
            app.ai.saveKey(key)
        } catch (e: AiException) {
            return@post call.respondSettings(app, mapOf("api_key" to "${e.message}."))
        }
        call.seeOther("/settings?saved=key")
    }

    post("/settings/api-key/remove") {
        app.ai.removeKey()
        call.seeOther("/settings?saved=removed")
    }

    post("/settings/web-search") {
        app.ai.setWebSearch(call.receiveSubmission().fields.has("web_search"))
        call.seeOther("/settings?saved=search")
    }
}

private suspend fun ApplicationCall.respondSettings(app: Services, errors: Map<String, String> = emptyMap()) {
    val key = app.ai.key()
    val webSearch = app.ai.webSearch()
    val status = if (errors.isEmpty()) HttpStatusCode.OK else HttpStatusCode.BadRequest
    respondPage("Settings", status) {
        pageHeader("Settings")
        when (queryParam("saved")) {
            "key" -> notice("API key saved. Items can now be filled in with AI.")
            "removed" -> notice("API key removed.")
            "search" -> notice(if (webSearch) "Web search is on." else "Web search is off.")
        }
        section("card") {
            div("card-title") {
                h2 { +"Fill in items with AI" }
                if (key is Ai.Key.Saved) span("badge badge-on") { +"On" } else span("badge") { +"Off" }
            }
            p {
                +"Organizer can fill in an item's tags, properties and notes from its photo, its name, or both, with "
                +"${AnthropicClaude.MODEL_NAME} by Anthropic. It needs an API key from "
                a("https://console.anthropic.com/settings/keys") { +"console.anthropic.com" }
                +". Anthropic charges that account about $0.0003 a photo and $0.0001 a name: 100 photos cost about 3¢."
            }
            when (key) {
                is Ai.Key.Saved -> div("api-key") {
                    span("muted") { +"API key" }
                    code { +"sk-ant-…${key.ending}" }
                    form("/settings/api-key/remove", method = FormMethod.post, classes = "inline-form") {
                        attributes["data-confirm"] = "Remove the API key? Filling in with AI stops until a key is added again."
                        button(type = ButtonType.submit, classes = "btn btn-sm btn-danger") { icon("trash"); +"Remove" }
                    }
                }
                Ai.Key.Unreadable -> div("notice notice-warn") {
                    icon("alert")
                    span {
                        +"An API key is saved, but it can't be decrypted here: it was saved on another computer, or the "
                        code { +"secret.key" }
                        +" file next to the database is gone. Enter it again."
                    }
                }
                Ai.Key.Missing -> Unit
            }
            form("/settings/api-key", method = FormMethod.post) {
                val label = if (key is Ai.Key.Saved) "Replace the API key" else "API key"
                field(label, "api_key", errors, hint = "Checked with Anthropic, then stored encrypted. It's never shown again, not even here.") {
                    div("input-row") {
                        passwordInput(name = "api_key") {
                            id = "api_key"
                            placeholder = "sk-ant-…"
                            required = true
                            attributes["autocomplete"] = "off"
                            attributes["spellcheck"] = "false"
                        }
                        button(type = ButtonType.submit, classes = "btn btn-primary") { +"Save key" }
                    }
                }
            }
            if (key is Ai.Key.Saved) {
                // app.js saves it as soon as the switch is flipped, and hides the Save button.
                form("/settings/web-search", method = FormMethod.post, classes = "setting") {
                    attributes["data-autosubmit"] = ""
                    label("switch") {
                        checkBoxInput(name = "web_search") {
                            attributes["role"] = "switch"
                            checked = webSearch
                        }
                        span("switch-track") {}
                        span("switch-text") {
                            strong { +"Web search" }
                            span { +"Let Claude search the web for details the photo or name doesn't give." }
                        }
                    }
                    p("hint") { +"About 1¢ a search on top of the usual price, and a few seconds longer. Claude searches only when it needs to." }
                    button(type = ButtonType.submit, classes = "btn btn-sm") {
                        attributes["data-autosubmit-button"] = ""
                        +"Save"
                    }
                }
            }
            div("fine-print") {
                p("hint") {
                    strong { +"Privacy: " }
                    +"the photo and name of an item you fill in with AI are sent to Anthropic. That happens when you press "
                    +"Fill in with AI, or choose a photo for a new item that has no name yet."
                }
                if (app.config.password == null) {
                    p("hint") {
                        +"Anyone who can open Organizer can use the key, though not see it. Set "
                        code { +"ORGANIZER_PASSWORD" }
                        +" to ask for a password."
                    }
                }
                if (!app.ai.tiedToMachine) {
                    p("hint") {
                        +"The key is encrypted, but not tied to this computer, because its ID couldn't be found. In Docker, set "
                        code { +"ORGANIZER_MACHINE_ID" }
                        +" (see the README)."
                    }
                }
            }
        }
        section("card") {
            h2 { +"Backup and export" }
            p {
                +"A backup is the whole database in one file, photos included. It's safe to download while Organizer is in use; "
                +"the README explains how to restore one. A saved API key stays encrypted in it."
            }
            div("toolbar") {
                a("/export/backup", classes = "btn") { icon("download"); +"Download backup" }
                a("/export/items.csv", classes = "btn") { icon("download"); +"Export CSV" }
            }
            p("hint") { +"The CSV file has one row per item, with its location, tags and properties, for Excel or Numbers." }
        }
    }
}
