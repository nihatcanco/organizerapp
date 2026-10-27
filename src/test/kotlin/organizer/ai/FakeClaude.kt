package organizer.ai

/**
 * Stands in for Anthropic's API: accepts keys that start with "sk-ant-good", answers every question with [answer],
 * and adds each question to [asked].
 */
class FakeClaude(
    private val apiKey: String,
    private val asked: MutableList<Question> = mutableListOf(),
    private val answer: String = """{"name": "Resistor 10 kΩ", "quantity": 100, "tags": ["resistor"], "properties": {"Package": "0603"}}""",
) : Claude {
    class Question(val apiKey: String, val text: String, val image: ClaudeImage?, val webSearch: Boolean)

    override fun check() {
        if (!apiKey.startsWith("sk-ant-good")) throw AiException("Anthropic didn't accept the API key. Check it in Settings")
    }

    override fun ask(question: String, image: ClaudeImage?, webSearch: Boolean): String {
        check()
        asked += Question(apiKey, question, image, webSearch)
        return answer
    }

    override fun close() {}
}
