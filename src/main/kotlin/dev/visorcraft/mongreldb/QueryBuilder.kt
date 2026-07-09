package dev.visorcraft.mongreldb

/**
 * Builds a request for the daemon's `/kit/query` endpoint, where conditions
 * push down to the engine's specialized indexes for sub-millisecond lookups.
 *
 * Condition parameters accept friendly aliases that are translated to the
 * server's exact on-wire keys before sending (see [where]):
 *
 * - `column` -> `column_id`
 * - `min` / `max` -> `lo` / `hi`
 * - `min_inclusive` -> `lo_inclusive`
 * - `max_inclusive` -> `hi_inclusive`
 *
 * The server's canonical keys are accepted directly too.
 *
 * ```kotlin
 * val rows = db.query("orders")
 *     .where("range", mapOf("column" to 3L, "min" to 100.0, "max" to 150.0))
 *     .projection(listOf(1L, 2L))
 *     .limit(100)
 *     .execute()
 * if (builder.truncated) {
 *     // result set hit the limit; more matches exist on the server
 * }
 * ```
 *
 * @property truncated Whether the most recent [execute] result was capped by
 *                     [limit]. `false` until [execute] has been called.
 */
public class QueryBuilder internal constructor(
    private val client: MongrelDB,
    private val table: String,
) {
    private val conditions: MutableList<Map<String, Any?>> = ArrayList()
    private var projection: List<Long>? = null
    private var limit: Long? = null

    @Volatile
    public var truncated: Boolean = false
        private set

    /**
     * Adds a native condition. Multiple [where] calls are AND-ed together.
     *
     * Available condition types include:
     *
     * - `pk` - exact primary-key match (`{value: pk}`)
     * - `bitmap_eq` - equality on a bitmap-indexed column
     * - `bitmap_in` - IN predicate on a bitmap-indexed column
     * - `range` - integer range predicate (lo/hi, inclusive)
     * - `range_f64` - float range predicate (lo/hi + lo_inclusive/hi_inclusive)
     * - `is_null` / `is_not_null` - null checks
     * - `fm_contains` / `fm_contains_all` - full-text substring search (FM-index)
     * - `ann` - dense vector similarity search (HNSW)
     * - `sparse_match` - sparse vector match
     * - `min_hash_similar` - MinHash similarity search
     *
     * @param condType the condition type
     * @param params the condition parameters (friendly aliases accepted)
     * @return this builder, for chaining
     */
    public fun where(condType: String, params: Map<String, Any?>): QueryBuilder {
        val entry: MutableMap<String, Any?> = LinkedHashMap()
        entry[condType] = normalizeCondition(condType, params)
        conditions.add(entry)
        return this
    }

    /**
     * Sets the column ids to return. `null` (the default) means all columns.
     *
     * @param columnIDs the projection, or `null` for all columns
     * @return this builder, for chaining
     */
    public fun projection(columnIDs: List<Long>?): QueryBuilder {
        this.projection = columnIDs?.let { ArrayList(it) }
        return this
    }

    /**
     * Caps the number of rows returned.
     *
     * @param limit the row limit
     * @return this builder, for chaining
     */
    public fun limit(limit: Long): QueryBuilder {
        this.limit = limit
        return this
    }

    /**
     * Builds the request payload that will be sent to `/kit/query`.
     */
    public fun build(): Map<String, Any?> {
        val payload: MutableMap<String, Any?> = LinkedHashMap()
        payload["table"] = table
        if (conditions.isNotEmpty()) {
            // The daemon expects externally-tagged conditions: [{type: {...}}, ...]
            payload["conditions"] = conditions
        }
        projection?.let { payload["projection"] = it }
        limit?.let { payload["limit"] = it }
        return payload
    }

    /**
     * Runs the query and returns the matching rows. Also records whether the
     * result was truncated by [limit]; check it with [truncated].
     *
     * @return the matching rows
     */
    public fun execute(): List<Row> {
        val body = client.post("/kit/query", build())
        val parsed = if (body.isEmpty()) null else MongrelDB.Json.parse(body)
        val rows: MutableList<Row> = ArrayList()
        var truncated = false
        if (parsed is Map<*, *>) {
            val r = parsed["rows"]
            if (r is List<*>) {
                for (row in r) {
                    @Suppress("UNCHECKED_CAST")
                    rows.add(if (row is Map<*, *>) row as Row else emptyMap())
                }
            }
            (parsed["truncated"] as? Boolean)?.let { truncated = it }
        }
        this.truncated = truncated
        return rows
    }

    public companion object {
        /**
         * Translates friendly parameter aliases to the server's canonical
         * on-wire keys. Both spellings are accepted, so callers may use whichever
         * is clearer.
         *
         * Generic aliases (applied to all condition types):
         *
         * - `column`        -> `column_id`
         * - `min`           -> `lo`
         * - `max`           -> `hi`
         * - `min_inclusive` -> `lo_inclusive`
         * - `max_inclusive` -> `hi_inclusive`
         *
         * Type-specific aliases:
         *
         * - `fm_contains` / `fm_contains_all`: `value` -> `pattern`
         *   (other types like `pk` / `bitmap_eq` use `value` as their canonical
         *   key, so the `value` -> `pattern` alias must NOT apply globally)
         */
        internal fun normalizeCondition(condType: String, params: Map<String, Any?>): Map<String, Any?> {
            val normalized: MutableMap<String, Any?> = LinkedHashMap(params.size)
            for ((key, value) in params) {
                val canonical =
                    when (key) {
                        "column" -> "column_id"
                        "min" -> "lo"
                        "max" -> "hi"
                        "min_inclusive" -> "lo_inclusive"
                        "max_inclusive" -> "hi_inclusive"
                        "value" ->
                            // The docs historically used "value" for the FTS
                            // pattern; the server's fm_contains key is
                            // "pattern". Only apply this for FTS conditions,
                            // since pk/bitmap_eq use "value" canonically.
                            if (condType == "fm_contains" || condType == "fm_contains_all") {
                                "pattern"
                            } else {
                                "value"
                            }
                        else -> key
                    }
                normalized[canonical] = value
            }
            return normalized
        }
    }
}
