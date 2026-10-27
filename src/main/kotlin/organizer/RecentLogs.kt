package organizer

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import java.time.Instant

/** A log line as the Logs page shows it; [details] is the stack trace of an error, if any. */
class LogEntry(val time: Instant, val level: String, val source: String, val message: String, val details: String?)

/**
 * Keeps the latest log lines in memory for the Logs page, next to the console output that `docker logs` shows.
 * logback.xml sends every line here; they're gone when the app restarts.
 */
class RecentLogs : AppenderBase<ILoggingEvent>() {
    override fun append(event: ILoggingEvent) {
        val entry = LogEntry(
            time = Instant.ofEpochMilli(event.timeStamp),
            level = event.level.toString(),
            source = event.loggerName.substringAfterLast('.'),
            message = event.formattedMessage,
            details = event.throwableProxy?.let(ThrowableProxyUtil::asString),
        )
        synchronized(entries) {
            entries.addLast(entry)
            if (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }

    companion object {
        const val MAX_ENTRIES = 500
        private val entries = ArrayDeque<LogEntry>()

        /** The kept lines, newest first. */
        fun entries(): List<LogEntry> = synchronized(entries) { entries.reversed() }
    }
}
