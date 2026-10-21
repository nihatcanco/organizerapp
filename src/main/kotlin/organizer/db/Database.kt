package organizer.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories

/**
 * A SQLite database file. Every call opens its own connection, which is cheap for SQLite and keeps
 * things thread-safe; WAL mode lets pages keep loading while a write is in progress.
 */
class Database private constructor(val path: Path) {
    private val reader = dataSource(SQLiteConfig.TransactionMode.DEFERRED)

    // IMMEDIATE takes the write lock up front, so concurrent writers wait for each other
    // (up to the busy timeout) instead of failing halfway through a transaction.
    private val writer = dataSource(SQLiteConfig.TransactionMode.IMMEDIATE)

    private fun dataSource(mode: SQLiteConfig.TransactionMode) = SQLiteDataSource(
        SQLiteConfig().apply {
            enforceForeignKeys(true)
            setBusyTimeout(10_000)
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
            setTransactionMode(mode)
        }
    ).apply { url = "jdbc:sqlite:$path" }

    suspend fun <T> read(block: (Connection) -> T): T = withContext(Dispatchers.IO) { transaction(reader, block) }

    suspend fun <T> write(block: (Connection) -> T): T = withContext(Dispatchers.IO) { transaction(writer, block) }

    /** Writes a consistent copy of the whole database to [target] (which must not exist or be empty). */
    suspend fun backupTo(target: Path): Unit = withContext(Dispatchers.IO) {
        reader.connection.use { it.execute("VACUUM INTO ?", target.toString()) }
    }

    private fun <T> transaction(source: SQLiteDataSource, block: (Connection) -> T): T =
        source.connection.use { connection ->
            connection.autoCommit = false
            try {
                block(connection).also { connection.commit() }
            } catch (e: Throwable) {
                connection.rollback()
                throw e
            }
        }

    private fun migrate() = transaction(writer) { connection ->
        val current = connection.queryOne("PRAGMA user_version") { it.getInt(1) } ?: 0
        check(current <= MIGRATIONS.size) {
            "$path uses schema version $current, but this version of the app only knows ${MIGRATIONS.size}. Please update the app."
        }
        connection.createStatement().use { statement ->
            MIGRATIONS.drop(current).flatten().forEach { statement.executeUpdate(it) }
            statement.executeUpdate("PRAGMA user_version = ${MIGRATIONS.size}")
        }
    }

    companion object {
        fun open(path: Path): Database {
            val absolute = path.absolute().normalize()
            absolute.parent?.createDirectories()
            return Database(absolute).also { it.migrate() }
        }
    }
}

internal fun PreparedStatement.bind(params: Array<out Any?>): PreparedStatement = apply {
    params.forEachIndexed { index, value ->
        when (value) {
            null -> setNull(index + 1, Types.NULL)
            is Boolean -> setInt(index + 1, if (value) 1 else 0)
            else -> setObject(index + 1, value)
        }
    }
}

internal fun <T> Connection.query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { statement ->
        statement.bind(params).executeQuery().use { rows ->
            buildList { while (rows.next()) add(map(rows)) }
        }
    }

internal fun <T> Connection.queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? =
    query(sql, *params, map = map).firstOrNull()

/** Runs an INSERT/UPDATE/DELETE and returns the number of changed rows. */
internal fun Connection.execute(sql: String, vararg params: Any?): Int =
    prepareStatement(sql).use { it.bind(params).executeUpdate() }

internal fun ResultSet.getLongOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }

internal fun ResultSet.getIntOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }
