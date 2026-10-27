package organizer.store

import organizer.db.Database
import organizer.db.execute
import organizer.db.queryOne
import organizer.model.AiSpending
import organizer.model.AiUsage
import java.time.Instant

/** What each question to Claude used and cost, for the totals in Settings. */
class UsageRepository(private val db: Database) {

    suspend fun record(model: String, usage: AiUsage) {
        db.write { c ->
            c.execute(
                "INSERT INTO ai_requests (at, model, input_tokens, output_tokens, web_searches, cost) VALUES (?, ?, ?, ?, ?, ?)",
                System.currentTimeMillis(), model, usage.inputTokens, usage.outputTokens, usage.webSearches, usage.dollars,
            )
        }
    }

    /** The questions asked at [from] or later, or all of them. */
    suspend fun spending(from: Instant? = null): AiSpending = db.read { c ->
        c.queryOne(
            "SELECT count(*), coalesce(sum(web_searches), 0), coalesce(sum(cost), 0), min(at) FROM ai_requests WHERE at >= ?",
            from?.toEpochMilli() ?: 0L,
        ) { row ->
            val first = row.getLong(4).takeUnless { row.wasNull() }
            AiSpending(row.getInt(1), row.getLong(2), row.getDouble(3), first?.let(Instant::ofEpochMilli))
        }!!
    }
}
