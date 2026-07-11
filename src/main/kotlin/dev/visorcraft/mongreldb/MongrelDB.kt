package dev.visorcraft.mongreldb

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/**
 * A type alias for a row of cells keyed by column id, used by the typed CRUD
 * helpers ([put], [upsert], and the [Transaction] staging methods). Each entry
 * maps a column id to its JSON-serializable value.
 *
 * The cells map is flattened to the daemon's flat `[col_id, value, ...]` array
 * before sending, so pair order is not significant.
 */
public typealias Cells = Map<Long, Any?>

/**
 * A decoded row from the daemon, keyed by the column id as a JSON-decoded string
 * (for example `"2"`).
 */
public typealias Row = Map<String, Any?>

/**
 * The MongrelDB HTTP client.
 *
 * A pure-Kotlin/JVM client for a running `mongreldb-server` daemon, built on the
 * JDK's [HttpURLConnection]. No external dependencies. The API mirrors the
 * MongrelDB Java, PHP, and Go clients: typed CRUD, a fluent query builder that
 * pushes conditions down to the engine's native indexes, idempotent batch
 * transactions, full SQL access, and schema introspection.
 *
 * Connect with a base URL:
 *
 * ```kotlin
 * val db = MongrelDB("http://127.0.0.1:8453")
 * val ok = db.health()
 * ```
 *
 * A [MongrelDB] instance is safe for concurrent use by multiple threads once
 * constructed: every request opens and closes its own [HttpURLConnection] and
 * the instance is immutable after configuration.
 *
 * @property baseURL The daemon base URL the client targets (any trailing slashes
 *                   are stripped).
 * @property token The Bearer token, when configured for `--auth-token` mode.
 * @property username The Basic-auth username, when configured for `--auth-users` mode.
 * @property password The Basic-auth password, or `""` when none is supplied.
 * @property connectTimeoutMillis The connect timeout, in milliseconds.
 * @property readTimeoutMillis The read timeout, in milliseconds.
 * @see <a href="https://www.mongreldb.com">MongrelDB</a>
 */
public class MongrelDB(
    url: String? = null,
    public val token: String? = null,
    public val username: String? = null,
    public val password: String? = null,
    private val connectTimeoutMillis: Int = 30_000,
    private val readTimeoutMillis: Int = 30_000,
) {
    /** The daemon address used when none is supplied. */
    public val baseURL: String = run {
        val base = url.takeUnless { it.isNullOrBlank() } ?: DEFAULT_BASE_URL
        base.trimEnd('/')
    }

    // ── Health & tables ───────────────────────────────────────────────────

    /**
     * Reports whether the daemon is reachable and healthy.
     *
     * @return `true` if the daemon answered `/health` with a 2xx status.
     */
    public fun health(): Boolean =
        try {
            get("/health")
            true
        } catch (e: MongrelDBException) {
            false
        }

    /**
     * Lists all table names in the database.
     *
     * @return a list of table names.
     */
    public fun tableNames(): List<String> {
        val body = get("/tables")
        if (body.isEmpty()) return emptyList()
        val parsed = Json.parse(body)
        if (parsed is List<*>) {
            return parsed.map { it?.toString() ?: "" }
        }
        throw QueryException("mongreldb: unexpected table-list response: ${Json.preview(body)}")
    }

    /** Pair returned by the {@code /history/retention} GET/PUT endpoint. */
    public data class HistoryRetention(val historyRetentionEpochs: Long, val earliestRetainedEpoch: Long)

    /** Sets the durable history-retention window and returns the post-update state. */
    public fun setHistoryRetentionEpochs(epochs: Long): HistoryRetention =
        parseHistoryRetention(doRequest("PUT", "/history/retention", mapOf("history_retention_epochs" to epochs)))

    /** Returns the current retention window and earliest retained epoch. */
    public fun historyRetention(): HistoryRetention = parseHistoryRetention(get("/history/retention"))

    /** Returns the configured history-retention window size in epochs. */
    public fun historyRetentionEpochs(): Long = historyRetention().historyRetentionEpochs

    /** Returns the earliest epoch still readable via {@code AS OF EPOCH} queries. */
    public fun earliestRetainedEpoch(): Long = historyRetention().earliestRetainedEpoch

    /** Package-visible helper used by live tests to commit a table and read the resulting epoch. */
    internal fun commitTable(table: String): Long {
        val body = post("/tables/${urlPathEscape(table)}/commit", null)
        val parsed = if (body.isEmpty()) null else Json.parse(body)
        if (parsed is Map<*, *>) {
            val epoch = parsed["epoch"]
            if (epoch is Number) return epoch.toLong()
        }
        throw QueryException("mongreldb: commit response missing epoch: ${Json.preview(body)}")
    }

    private fun parseHistoryRetention(body: ByteArray): HistoryRetention {
        val value = Json.parse(body) as? Map<*, *> ?: throw QueryException("mongreldb: malformed history retention response")
        return HistoryRetention(
            (value["history_retention_epochs"] as? Number)?.toLong() ?: throw QueryException("mongreldb: missing history_retention_epochs"),
            (value["earliest_retained_epoch"] as? Number)?.toLong() ?: throw QueryException("mongreldb: missing earliest_retained_epoch"),
        )
    }

    /**
     * Creates a table named [name] with the given columns and returns the
     * assigned table id.
     *
     * Each column is a [Map] sent verbatim to the daemon. Recognized keys are
     * `id`, `name`, `ty`, `primary_key`, and `nullable`.
     *
     * @param name the table name
     * @param columns the column descriptors
     * @return the assigned table id, or `0L` when the daemon omits one
     */
    public fun createTable(name: String, columns: List<Map<String, Any?>>): Long {
        return createTable(name, columns, null)
    }

    /** Creates a table with an optional top-level engine constraints object. */
    public fun createTable(
        name: String,
        columns: List<Map<String, Any?>>,
        constraints: Map<String, Any?>?,
    ): Long {
        val payload: MutableMap<String, Any?> = LinkedHashMap()
        payload["name"] = name
        payload["columns"] = columns
        if (constraints != null) payload["constraints"] = constraints
        val body = post("/kit/create_table", payload)
        val parsed = if (body.isEmpty()) null else Json.parse(body)
        if (parsed is Map<*, *>) {
            val id = parsed["table_id"]
            if (id is Number) return id.toLong()
        }
        return 0L
    }

    /**
     * Drops a table by name.
     *
     * @param name the table name
     */
    public fun dropTable(name: String) {
        delete("/tables/${urlPathEscape(name)}")
    }

    /**
     * Returns the row count for a table.
     *
     * @param table the table name
     * @return the number of rows
     */
    public fun count(table: String): Long {
        val body = get("/tables/${urlPathEscape(table)}/count")
        val parsed = if (body.isEmpty()) null else Json.parse(body)
        if (parsed is Map<*, *>) {
            val c = parsed["count"]
            if (c is Number) return c.toLong()
        }
        throw QueryException("mongreldb: malformed count response: $body")
    }

    // ── CRUD (via the Kit typed transaction endpoint) ─────────────────────

    /**
     * Inserts a row. [idempotencyKey], when non-blank, makes the commit safe to
     * retry - the daemon returns the original result on duplicate commits.
     *
     * @param table the target table
     * @param cells a column-id-to-value map (flattened to the server's
     *              `[col_id, value, ...]` array before sending)
     * @param idempotencyKey an idempotency key, or `null`
     * @return the per-operation result object (the first element of the server's
     *         results array), or an empty map if none
     */
    public fun put(table: String, cells: Cells, idempotencyKey: String? = null): Row {
        val op: MutableMap<String, Any?> = LinkedHashMap()
        val put: MutableMap<String, Any?> = LinkedHashMap()
        put["table"] = table
        put["cells"] = flattenCells(cells)
        op["put"] = put
        val results = commitOne(listOf(op), idempotencyKey)
        return firstResult(results)
    }

    /**
     * Inserts a row, or updates it on a primary-key conflict.
     * [updateCells], when non-null, supplies the values written on conflict;
     * `null` means DO NOTHING.
     *
     * @param table the target table
     * @param cells the column-id-to-value map to insert
     * @param updateCells the values written on conflict, or `null`
     * @param idempotencyKey an idempotency key, or `null`
     * @return the per-operation result object, or an empty map if none
     */
    public fun upsert(
        table: String,
        cells: Cells,
        updateCells: Cells? = null,
        idempotencyKey: String? = null,
    ): Row {
        val op: MutableMap<String, Any?> = LinkedHashMap()
        val upsert: MutableMap<String, Any?> = LinkedHashMap()
        upsert["table"] = table
        upsert["cells"] = flattenCells(cells)
        if (updateCells != null) {
            upsert["update_cells"] = flattenCells(updateCells)
        }
        op["upsert"] = upsert
        val results = commitOne(listOf(op), idempotencyKey)
        return firstResult(results)
    }

    /**
     * Removes a row by its internal row id.
     *
     * @param table the target table
     * @param rowId the internal row id
     */
    public fun delete(table: String, rowId: Long) {
        val op: MutableMap<String, Any?> = LinkedHashMap()
        val del: MutableMap<String, Any?> = LinkedHashMap()
        del["table"] = table
        del["row_id"] = rowId
        op["delete"] = del
        commitOne(listOf(op), null)
    }

    /**
     * Removes a row by its primary-key value.
     *
     * @param table the target table
     * @param pk the primary-key value
     */
    public fun deleteByPk(table: String, pk: Any?) {
        val op: MutableMap<String, Any?> = LinkedHashMap()
        val del: MutableMap<String, Any?> = LinkedHashMap()
        del["table"] = table
        del["pk"] = pk
        op["delete_by_pk"] = del
        commitOne(listOf(op), null)
    }

    /** Backwards-compatible alias mirroring the Java client spelling. */
    public fun deleteByPK(table: String, pk: Any?): Unit = deleteByPk(table, pk)

    // commitOne sends a single-op transaction and returns the results array.
    private fun commitOne(ops: List<Map<String, Any?>>, idempotencyKey: String?): List<Row> {
        val payload: MutableMap<String, Any?> = LinkedHashMap()
        payload["ops"] = ops
        if (!idempotencyKey.isNullOrBlank()) {
            payload["idempotency_key"] = idempotencyKey
        }
        val body = post("/kit/txn", payload)
        return decodeResults(body)
    }

    // ── Query ─────────────────────────────────────────────────────────────

    /**
     * Starts a fluent [QueryBuilder] against [table].
     *
     * @param table the table to query
     * @return a new query builder
     */
    public fun query(table: String): QueryBuilder = QueryBuilder(this, table)

    // ── SQL ───────────────────────────────────────────────────────────────

    /**
     * Executes a SQL statement via the `/sql` endpoint, requesting JSON output.
     * The server returns a JSON array of row objects keyed by column name, e.g.
     * `[{"id": 1, "name": "Alice", "score": 95.5}]`. For statements that yield
     * no rows (DDL/DML), the body is empty and an empty list is returned.
     *
     * @param sql the SQL statement
     * @return the decoded rows, or an empty list
     */
    public fun sql(sql: String): List<Row> {
        val payload: MutableMap<String, Any?> = LinkedHashMap()
        payload["sql"] = sql
        payload["format"] = "json"
        val body = post("/sql", payload)
        val trimmed = trim(body)
        if (trimmed.isEmpty()) return emptyList()
        // JSON format requested; a leading '{' is a single object (e.g. an error
        // envelope), not a row set, so return an empty list. A '[' begins the
        // row array to decode.
        val first = trimmed[0]
        if (first == '['.code.toByte()) {
            val parsed = Json.parse(body)
            if (parsed is List<*>) {
                return parsed.map { row ->
                    @Suppress("UNCHECKED_CAST")
                    if (row is Map<*, *>) row as Row else emptyMap()
                }
            }
        }
        return emptyList()
    }

    // ── Schema ────────────────────────────────────────────────────────────

    /**
     * Returns the full schema catalog: a table-name-to-descriptor map.
     */
    public fun schema(): Map<String, Row> {
        val body = get("/kit/schema")
        val parsed = if (body.isEmpty()) null else Json.parse(body)
        val out: MutableMap<String, Row> = LinkedHashMap()
        if (parsed is Map<*, *>) {
            val tables = parsed["tables"]
            if (tables is Map<*, *>) {
                for ((key, value) in tables) {
                    @Suppress("UNCHECKED_CAST")
                    if (value is Map<*, *>) out[key.toString()] = value as Row
                }
            }
        }
        return out
    }

    /**
     * Returns the descriptor for a single table.
     *
     * @param table the table name
     */
    public fun schemaFor(table: String): Row {
        val body = get("/kit/schema/${urlPathEscape(table)}")
        val parsed = if (body.isEmpty()) null else Json.parse(body)
        @Suppress("UNCHECKED_CAST")
        return if (parsed is Map<*, *>) parsed as Row else emptyMap()
    }

    // ── Maintenance ───────────────────────────────────────────────────────

    /** Merges sorted runs across all tables. Returns the daemon's response object. */
    public fun compact(): Row = postDecode("/compact")

    /**
     * Merges sorted runs for a single table.
     *
     * @param table the table to compact
     */
    public fun compactTable(table: String): Row =
        postDecode("/tables/${urlPathEscape(table)}/compact")

    // postDecode POSTs an empty body and decodes the JSON object response.
    private fun postDecode(path: String): Row {
        val body = post(path, null)
        val parsed = if (body.isEmpty()) null else Json.parse(body)
        @Suppress("UNCHECKED_CAST")
        return if (parsed is Map<*, *>) parsed as Row else emptyMap()
    }

    // ── Transactions ──────────────────────────────────────────────────────

    /**
     * Starts a new batch transaction. Operations staged on the returned
     * [Transaction] are committed atomically in a single `/kit/txn` request.
     */
    public fun beginTransaction(): Transaction = Transaction(this)

    /** Alias mirroring the Java client's `begin()`. */
    public fun begin(): Transaction = Transaction(this)

    /**
     * Sends a batch of staged operations atomically. Exposed for the
     * [Transaction] type; returns the per-operation results array, or `null`
     * when [ops] is empty.
     */
    internal fun commitTxn(ops: List<Map<String, Any?>>, idempotencyKey: String?): List<Row>? {
        if (ops.isEmpty()) return null
        val payload: MutableMap<String, Any?> = LinkedHashMap()
        payload["ops"] = ops
        if (!idempotencyKey.isNullOrBlank()) {
            payload["idempotency_key"] = idempotencyKey
        }
        val body = post("/kit/txn", payload)
        return decodeResults(body)
    }

    // ── HTTP plumbing ─────────────────────────────────────────────────────

    internal fun get(path: String): ByteArray = doRequest("GET", path, null)

    internal fun post(path: String, body: Any?): ByteArray = doRequest("POST", path, body)

    private fun delete(path: String): ByteArray = doRequest("DELETE", path, null)

    /**
     * Builds and runs one request against [baseURL]. The server's JSON
     * extractors require an explicit `Content-Type` header on any request
     * carrying a JSON body, so one is added whenever the body is non-null.
     * Non-2xx responses are mapped to typed client exceptions via
     * [toException].
     */
    private fun doRequest(method: String, path: String, body: Any?): ByteArray {
        val url = URI.create("$baseURL/${stripLeadingSlash(path)}").toURL()
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            applyAuth(this)
        }

        try {
            if (body != null) {
                val payload = Json.toBytes(body)
                writeRequest(conn, payload)
            }

            val data: ByteArray =
                try {
                    val stream: InputStream =
                        if (conn.responseCode in 200..299) {
                            conn.inputStream
                        } else {
                            conn.errorStream ?: EmptyInputStream
                        }
                    readBounded(stream, MAX_RESPONSE_BYTES, method, path)
                } catch (e: IOException) {
                    throw QueryException(
                        "mongreldb: request $method $path failed: ${e.message}",
                        e,
                    )
                }

            val status = conn.responseCode
            if (status !in 200..299) {
                throw toException(status, data)
            }
            return data
        } finally {
            conn.disconnect()
        }
    }

    private fun writeRequest(conn: HttpURLConnection, payload: ByteArray) {
        try {
            // Stream the body with a known length so the connection does not
            // buffer the whole payload in memory.
            conn.setFixedLengthStreamingMode(payload.size.toLong())
            (conn.outputStream as OutputStream).use { it.write(payload) }
        } catch (e: IOException) {
            throw QueryException(
                "mongreldb: failed to write request body: ${e.message}",
                e,
            )
        }
    }

    // readBounded reads up to limit+1 bytes so an oversized body can be
    // detected without buffering an unbounded amount. A body larger than limit
    // throws a QueryException.
    private fun readBounded(
        stream: InputStream,
        limit: Long,
        method: String,
        path: String,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) {
                throw QueryException(
                    "mongreldb: request $method $path response body exceeds $limit bytes",
                )
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    // applyAuth sets the Authorization header according to the configured
    // credentials. A bearer token takes precedence over basic auth.
    private fun applyAuth(conn: HttpURLConnection) {
        if (!token.isNullOrBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        } else if (!username.isNullOrBlank()) {
            val creds = "$username:${password ?: ""}"
            val encoded =
                Base64
                    .getEncoder()
                    .encodeToString(creds.toByteArray(StandardCharsets.UTF_8))
            conn.setRequestProperty("Authorization", "Basic $encoded")
        }
    }

    public companion object {
        /** The daemon address used when none is supplied. */
        public const val DEFAULT_BASE_URL: String = "http://127.0.0.1:8453"

        /**
         * Caps the size of a response body read from the daemon (256 MB).
         * Bodies larger than this throw a [QueryException].
         */
        public const val MAX_RESPONSE_BYTES: Long = 268435456L

        /**
         * Flattens a column-id-to-value map to the server's flat
         * `[col_id, value, col_id, value, ...]` array. Pair order is not
         * significant - each value is preceded by its own column id.
         */
        @JvmStatic
        internal fun flattenCells(cells: Cells): List<Any?> {
            val flat = ArrayList<Any?>(cells.size * 2)
            for ((colId, value) in cells) {
                flat.add(colId)
                flat.add(value)
            }
            return flat
        }

        // decodeResults pulls the results array out of a /kit/txn response.
        internal fun decodeResults(body: ByteArray): List<Row> {
            if (trim(body).isEmpty()) return emptyList()
            val parsed = Json.parse(body)
            if (parsed !is Map<*, *>) {
                throw QueryException("mongreldb: decode txn response: unexpected JSON")
            }
            val results = parsed["results"]
            val out: MutableList<Row> = ArrayList()
            if (results is List<*>) {
                for (r in results) {
                    @Suppress("UNCHECKED_CAST")
                    out.add(if (r is Map<*, *>) r as Row else emptyMap())
                }
            }
            return out
        }

        // firstResult returns the first element of results, or an empty map.
        internal fun firstResult(results: List<Row>): Row =
            if (results.isEmpty()) emptyMap() else results[0]

        /** An empty, never-closing input stream for the error-path branch. */
        private object EmptyInputStream : InputStream() {
            override fun read(): Int = -1
        }

        private fun trim(b: ByteArray): ByteArray {
            var s = 0
            var e = b.size
            while (s < e && b[s].isJsonSpace()) s++
            while (e > s && b[e - 1].isJsonSpace()) e--
            return if (s == 0 && e == b.size) b else b.copyOfRange(s, e)
        }

        private fun Byte.isJsonSpace(): Boolean =
            this == ' '.code.toByte() ||
                this == '\t'.code.toByte() ||
                this == '\n'.code.toByte() ||
                this == '\r'.code.toByte()

        private fun stripLeadingSlash(s: String): String =
            s.dropWhile { it == '/' }

        /**
         * Percent-encodes a path segment (used for table names that may contain
         * characters unsafe in a URL). The forward slash is encoded so a table
         * name cannot inject an extra path segment; only RFC 3986 unreserved
         * characters pass through unencoded.
         */
        internal fun urlPathEscape(seg: String): String {
            val out = StringBuilder(seg.length)
            for (c in seg) {
                when {
                    c == '-' || c == '_' || c == '.' || c == '~' ||
                        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' -> {
                        out.append(c)
                    }
                    else -> {
                        for (bb in c.toString().toByteArray(StandardCharsets.UTF_8)) {
                            out.append('%')
                            out.append(hexChar((bb.toInt() ushr 4) and 0x0F))
                            out.append(hexChar(bb.toInt() and 0x0F))
                        }
                    }
                }
            }
            return out.toString()
        }

        private fun hexChar(n: Int): Char = if (n < 10) ('0' + n) else ('A' + (n - 10))

        /**
         * Maps an HTTP status code and response body to a typed exception. It
         * best-effort decodes the server's JSON error envelope
         * (`{error:{message,code,op_index}}`) and falls back to the raw body.
         */
        internal fun toException(status: Int, body: ByteArray): MongrelDBException {
            var message: String? = null
            var code: String? = null
            var opIndex: Int? = null

            val trimmed = trim(body)
            if (trimmed.isNotEmpty() && trimmed[0] == '{'.code.toByte()) {
                val parsed: Any? =
                    try {
                        Json.parse(body)
                    } catch (e: RuntimeException) {
                        null
                    }
                if (parsed is Map<*, *>) {
                    // Prefer the nested {"error": {...}} envelope.
                    val err = parsed["error"]
                    if (err is Map<*, *>) {
                        message = err["message"]?.toString()
                        code = err["code"]?.toString()
                        opIndex = (err["op_index"] as? Number)?.toInt()
                    }
                    // Fall back to a flat {"message": ..., "code": ...} object.
                    if (message == null && code == null && opIndex == null) {
                        message = parsed["message"]?.toString()
                        code = parsed["code"]?.toString()
                    }
                }
            }
            if (message == null && body.isNotEmpty()) {
                message = String(body, StandardCharsets.UTF_8)
            }

            if (message.isNullOrBlank()) {
                message =
                    when (status) {
                        401, 403 -> "authentication failed ($status)"
                        404 -> "resource not found"
                        409 -> "constraint violation"
                        else -> "server error ($status)"
                    }
            }

            if (message.startsWith("not found:")) {
                return NotFoundException(message, 404, code, opIndex)
            }

            return when (status) {
                401, 403 -> AuthException(message, status, code, opIndex)
                404 -> NotFoundException(message, status, code, opIndex)
                409 -> ConflictException(message, status, code, opIndex)
                else -> QueryException(message, status, code, opIndex)
            }
        }
    }

    /**
     * Minimal JSON codec used internally by the client. It encodes and decodes
     * `Map<String, Any?>`, `List<Any?>`, [Number], [Boolean], [String], and
     * `null` - the exact shape the daemon's JSON API uses - without pulling in a
     * third-party dependency.
     *
     * This is intentionally narrow: it is not a general-purpose JSON library.
     */
    internal object Json {
        /** Encodes a value to UTF-8 JSON bytes. */
        fun toBytes(value: Any?): ByteArray {
            val sb = StringBuilder()
            write(sb, value)
            return sb.toString().toByteArray(StandardCharsets.UTF_8)
        }

        /** Parses UTF-8 JSON bytes into `Map`/`List`/primitive. */
        fun parse(body: ByteArray): Any? {
            val src = String(body, StandardCharsets.UTF_8)
            val parser = Parser(src)
            parser.skipWs()
            val value = parser.readValue()
            parser.skipWs()
            if (parser.pos < src.length) {
                throw QueryException("mongreldb: trailing JSON content at ${parser.pos}")
            }
            return value
        }

        /** A short, safe preview of a body for error messages. */
        fun preview(body: ByteArray): String {
            val s = String(body, StandardCharsets.UTF_8)
            return if (s.length > 120) s.substring(0, 120) + "..." else s
        }

        private fun write(sb: StringBuilder, value: Any?) {
            when (value) {
                null -> sb.append("null")
                is Map<*, *> -> {
                    sb.append('{')
                    var first = true
                    for ((k, v) in value) {
                        if (!first) sb.append(',')
                        first = false
                        writeString(sb, k.toString())
                        sb.append(':')
                        write(sb, v)
                    }
                    sb.append('}')
                }
                is List<*> -> {
                    sb.append('[')
                    var first = true
                    for (o in value) {
                        if (!first) sb.append(',')
                        first = false
                        write(sb, o)
                    }
                    sb.append(']')
                }
                is BooleanArray -> {
                    sb.append('[')
                    var first = true
                    for (o in value) {
                        if (!first) sb.append(',')
                        first = false
                        write(sb, o)
                    }
                    sb.append(']')
                }
                is ByteArray -> write(sb, value.toList())
                is ShortArray -> write(sb, value.toList())
                is IntArray -> write(sb, value.toList())
                is LongArray -> write(sb, value.toList())
                is FloatArray -> {
                    sb.append('[')
                    var first = true
                    for (f in value) {
                        if (!first) sb.append(',')
                        first = false
                        write(sb, f.toDouble())
                    }
                    sb.append(']')
                }
                is DoubleArray -> {
                    sb.append('[')
                    var first = true
                    for (d in value) {
                        if (!first) sb.append(',')
                        first = false
                        write(sb, d)
                    }
                    sb.append(']')
                }
                is String -> writeString(sb, value)
                is Boolean -> sb.append(value.toString())
                // Reject non-finite doubles/floats so we never emit invalid JSON
                // tokens like "NaN" or "Infinity".
                is Double -> {
                    if (value.isInfinite() || value.isNaN()) {
                        throw QueryException(
                            "mongreldb: cannot encode non-finite number $value as JSON",
                        )
                    }
                    sb.append(value.toString())
                }
                is Float -> {
                    if (value.isInfinite() || value.isNaN()) {
                        throw QueryException(
                            "mongreldb: cannot encode non-finite number $value as JSON",
                        )
                    }
                    sb.append(value.toString())
                }
                is Number -> sb.append(value.toString())
                is Char -> writeString(sb, value.toString())
                is Enum<*> -> writeString(sb, value.name)
                else -> {
                    // Fallback: treat unknown types as their string form.
                    writeString(sb, value.toString())
                }
            }
        }

        private fun writeString(sb: StringBuilder, s: String) {
            sb.append('"')
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    else ->
                        if (c.code < 0x20) {
                            sb.append(String.format(Locale.ROOT, "\\u%04x", c.code))
                        } else {
                            sb.append(c)
                        }
                }
            }
            sb.append('"')
        }
    }

    /** A tiny recursive-descent JSON parser. */
    private class Parser(val src: String) {
        var pos: Int = 0

        fun skipWs() {
            while (pos < src.length) {
                val c = src[pos]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++
                } else {
                    break
                }
            }
        }

        fun readValue(): Any? {
            skipWs()
            if (pos >= src.length) {
                throw QueryException("mongreldb: unexpected end of JSON")
            }
            val c = src[pos]
            return when (c) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> readBool()
                'n' -> readNull()
                else -> readNumber()
            }
        }

        fun readObject(): MutableMap<String, Any?> {
            expect('{')
            val obj: MutableMap<String, Any?> = LinkedHashMap()
            skipWs()
            if (peek() == '}') {
                pos++
                return obj
            }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                expect(':')
                val value = readValue()
                obj[key] = value
                skipWs()
                when (next()) {
                    ',' -> continue
                    '}' -> break
                    else -> throw QueryException("mongreldb: expected ',' or '}' at ${pos - 1}")
                }
            }
            return obj
        }

        fun readArray(): MutableList<Any?> {
            expect('[')
            val arr: MutableList<Any?> = ArrayList()
            skipWs()
            if (peek() == ']') {
                pos++
                return arr
            }
            while (true) {
                arr.add(readValue())
                skipWs()
                when (next()) {
                    ',' -> continue
                    ']' -> break
                    else -> throw QueryException("mongreldb: expected ',' or ']' at ${pos - 1}")
                }
            }
            return arr
        }

        fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                if (c == '"') return sb.toString()
                if (c == '\\') {
                    if (pos >= src.length) {
                        throw QueryException("mongreldb: unterminated escape")
                    }
                    val e = src[pos++]
                    when (e) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            if (pos + 4 > src.length) {
                                throw QueryException("mongreldb: bad \\u escape")
                            }
                            val hex = src.substring(pos, pos + 4)
                            pos += 4
                            try {
                                sb.append(hex.toInt(16).toChar())
                            } catch (ex: NumberFormatException) {
                                throw QueryException("mongreldb: bad \\u escape: $hex")
                            }
                        }
                        else -> throw QueryException("mongreldb: bad escape '\\$e'")
                    }
                } else {
                    sb.append(c)
                }
            }
            throw QueryException("mongreldb: unterminated string")
        }

        fun readBool(): Boolean =
            when {
                src.startsWith("true", pos) -> {
                    pos += 4
                    true
                }
                src.startsWith("false", pos) -> {
                    pos += 5
                    false
                }
                else -> throw QueryException("mongreldb: invalid literal at $pos")
            }

        fun readNull(): Any? {
            if (src.startsWith("null", pos)) {
                pos += 4
                return null
            }
            throw QueryException("mongreldb: invalid literal at $pos")
        }

        fun readNumber(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (pos < src.length) {
                val c = src[pos]
                if (c in '0'..'9' || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    pos++
                } else {
                    break
                }
            }
            val num = src.substring(start, pos)
            if (num.isEmpty()) {
                throw QueryException("mongreldb: invalid number at $start")
            }
            // Preserve integer precision for values that fit in a long; use
            // double otherwise (including exponents and fractions).
            return if ('.' !in num && 'e' !in num && 'E' !in num) {
                try {
                    num.toLong()
                } catch (ex: NumberFormatException) {
                    BigInteger(num)
                }
            } else {
                num.toDouble()
            }
        }

        private fun peek(): Char = if (pos >= src.length) '\u0000' else src[pos]

        private fun next(): Char {
            if (pos >= src.length) {
                throw QueryException("mongreldb: unexpected end of JSON")
            }
            return src[pos++]
        }

        private fun expect(c: Char) {
            val actual = next()
            if (actual != c) {
                throw QueryException("mongreldb: expected '$c' but got '$actual' at ${pos - 1}")
            }
        }
    }
}
