package organizer.ai

import organizer.model.AiUsage

/**
 * Stands in for Anthropic's API: accepts keys that start with "sk-ant-good", answers every question with [answer],
 * and adds each question to [asked]. Every answer used [USAGE].
 */
class FakeClaude(
    private val apiKey: String,
    private val asked: MutableList<Question> = mutableListOf(),
    private val answer: () -> String = { """{"name": "Resistor 10 kΩ", "quantity": 100, "tags": ["resistor"], "properties": {"Package": "0603"}}""" },
) : Claude {
    class Question(val apiKey: String, val text: String, val image: ClaudeImage?, val webSearch: Boolean)

    override fun check() {
        if (!apiKey.startsWith("sk-ant-good")) throw AiException("Anthropic didn't accept the API key. Check it in Settings")
    }

    override fun ask(question: String, image: ClaudeImage?, webSearch: Boolean): Answer {
        check()
        asked += Question(apiKey, question, image, webSearch)
        return Answer(answer(), USAGE)
    }

    override fun close() {}

    companion object {
        val USAGE = AiUsage(inputTokens = 12_000, outputTokens = 150, webSearches = 1, dollars = 0.012_75)
    }
}

/** Stands in for [WebPhotos]: has [photo] at every page, or fails with [error], and adds the pages it was given to [searched]. */
class FakePhotos(var photo: WebPhoto?, val searched: MutableList<List<String>> = mutableListOf()) : PhotoFinder {
    var error: Exception? = null

    override suspend fun find(urls: List<String>): WebPhoto? {
        searched += urls
        error?.let { throw it }
        return photo
    }
}
