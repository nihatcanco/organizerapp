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
import kotlinx.html.code
import kotlinx.html.dd
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.form
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.p
import kotlinx.html.passwordInput
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.strong
import organizer.ai.Ai
import organizer.ai.AiException
import organizer.ai.AnthropicClaude
import java.time.LocalDate
import java.time.ZoneId

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
}

private suspend fun ApplicationCall.respondSettings(app: Services, errors: Map<String, String> = emptyMap()) {
    val key = app.ai.key()
    val spent = app.ai.spending()
    val spentThisMonth = app.ai.spending(LocalDate.now().withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant())
    val status = if (errors.isEmpty()) HttpStatusCode.OK else HttpStatusCode.BadRequest
    respondPage("Settings", status) {
        pageHeader("Settings")
        when (queryParam("saved")) {
            "key" -> notice("API key saved. Items can now be filled in with AI.")
            "removed" -> notice("API key removed.")
        }
        section("card") {
            div("card-title") {
                h2 { +"Fill in items with AI" }
                if (key is Ai.Key.Saved) span("badge badge-on") { +"On" } else span("badge") { +"Off" }
            }
            p {
                +"Organizer can fill in an item's tags, properties and notes from its photo, its name, or both, and find a photo "
                +"for an item added by name. Claude searches the web for what the photo or name doesn't say. It needs an API key from "
                a("https://console.anthropic.com/settings/keys") { +"console.anthropic.com" }
                +". Anthropic charges that account about $0.0003 a photo and $0.0001 a name, plus 1¢ for each web search."
            }
            dl("properties ai-facts") {
                dt { +"Model" }
                dd {
                    +"${AnthropicClaude.MODEL_NAME} with web search "
                    code { +AnthropicClaude.MODEL }
                }
                dt { +"Spent" }
                dd {
                    if (spent.requests == 0) {
                        +"Nothing yet"
                    } else {
                        +"${formatDollars(spentThisMonth.dollars)} this month, ${formatDollars(spent.dollars)} in total"
                        span("hint") {
                            +"${plural(spent.requests, "question")} and ${plural(spent.webSearches.toInt(), "web search", "web searches")} "
                            +"since ${formatDate(spent.since!!)}, at Anthropic's list prices. The bill itself is at "
                            a("https://console.anthropic.com/settings/billing") { +"console.anthropic.com" }
                            +"."
                        }
                    }
                }
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
            div("fine-print") {
                p("hint") {
                    strong { +"Privacy: " }
                    +"the photo and name of an item you fill in with AI are sent to Anthropic. That happens when you press "
                    +"Fill in with AI, or choose a photo for a new item that has no name yet. A photo found for an item is "
                    +"downloaded by Organizer from the page Claude found."
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
