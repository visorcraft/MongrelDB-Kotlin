package com.visorcraft.mongreldb

/**
 * Fluent builder for `POST /kit/search` — multi-retriever hybrid search with
 * reciprocal-rank fusion and optional exact-vector rerank.
 *
 * Wire format matches the daemon KitSearchRequest (flattened retrievers).
 */
public class SearchBuilder internal constructor(
    private val client: MongrelDB,
    private val table: String,
) {
    private val must: MutableList<Map<String, Any?>> = mutableListOf()
    private val retrievers: MutableList<Map<String, Any?>> = mutableListOf()
    private var fusion: Map<String, Any?> =
        mapOf("reciprocal_rank" to mapOf("constant" to 60))
    private var rerank: Map<String, Any?>? = null
    private var limit: Long = 10
    private var projection: List<Long>? = null
    private var explain: Boolean = false
    private var cursor: String? = null

    /** Hard filter (same condition shapes as [QueryBuilder.where]). */
    public fun must(type: String, params: Map<String, Any?>): SearchBuilder {
        must += mapOf(type to QueryBuilder.normalizeCondition(type, params))
        return this
    }

    public fun annRetriever(
        name: String,
        columnId: Long,
        query: List<Double>,
        k: Long = 64,
        weight: Double = 1.0,
    ): SearchBuilder {
        retrievers +=
            mapOf(
                "name" to name,
                "weight" to weight,
                "ann" to
                    mapOf(
                        "column_id" to columnId,
                        "query" to query,
                        "k" to k,
                    ),
            )
        return this
    }

    /** [terms] is a list of `[tokenId, weight]` pairs. */
    public fun sparseRetriever(
        name: String,
        columnId: Long,
        terms: List<List<Number>>,
        k: Long = 64,
        weight: Double = 1.0,
    ): SearchBuilder {
        val pairs = terms.map { listOf(it[0].toLong(), it[1].toDouble()) }
        retrievers +=
            mapOf(
                "name" to name,
                "weight" to weight,
                "sparse" to
                    mapOf(
                        "column_id" to columnId,
                        "query" to pairs,
                        "k" to k,
                    ),
            )
        return this
    }

    public fun minHashRetriever(
        name: String,
        columnId: Long,
        members: List<String>,
        k: Long = 64,
        weight: Double = 1.0,
    ): SearchBuilder {
        retrievers +=
            mapOf(
                "name" to name,
                "weight" to weight,
                "min_hash" to
                    mapOf(
                        "column_id" to columnId,
                        "members" to members,
                        "k" to k,
                    ),
            )
        return this
    }

    public fun fusion(constant: Int = 60): SearchBuilder {
        fusion = mapOf("reciprocal_rank" to mapOf("constant" to maxOf(1, constant)))
        return this
    }

    /** [metric] is `cosine`, `dot_product`, or `euclidean`. */
    public fun exactRerank(
        embeddingColumn: Long,
        query: List<Double>,
        metric: String = "cosine",
        candidateLimit: Long = 64,
        weight: Double = 1.0,
    ): SearchBuilder {
        rerank =
            mapOf(
                "exact_vector" to
                    mapOf(
                        "embedding_column" to embeddingColumn,
                        "query" to query,
                        "metric" to metric,
                        "candidate_limit" to candidateLimit,
                        "weight" to weight,
                    ),
            )
        return this
    }

    public fun limit(limit: Long): SearchBuilder {
        this.limit = limit
        return this
    }

    public fun projection(columnIds: List<Long>): SearchBuilder {
        projection = columnIds
        return this
    }

    public fun explain(on: Boolean = true): SearchBuilder {
        explain = on
        return this
    }

    public fun cursor(cursor: String?): SearchBuilder {
        this.cursor = cursor
        return this
    }

    public fun build(): Map<String, Any?> {
        require(retrievers.isNotEmpty()) { "search requires at least one retriever" }
        require(limit > 0) { "search limit must be positive" }
        val payload =
            mutableMapOf<String, Any?>(
                "table" to table,
                "retrievers" to retrievers.toList(),
                "fusion" to fusion,
                "limit" to limit,
            )
        if (must.isNotEmpty()) payload["must"] = must.toList()
        if (rerank != null) payload["rerank"] = rerank
        if (projection != null) payload["projection"] = projection
        if (explain) payload["explain"] = true
        if (!cursor.isNullOrEmpty()) payload["cursor"] = cursor
        return payload
    }

    /** Execute hybrid search; returns body with `hits` (and optional cursors). */
    @Suppress("UNCHECKED_CAST")
    public fun execute(): Map<String, Any?> {
        val body = client.post("/kit/search", build())
        val parsed = if (body.isEmpty()) null else MongrelDB.Json.parse(body)
        return if (parsed is Map<*, *>) {
            parsed as Map<String, Any?>
        } else {
            mapOf("hits" to emptyList<Any?>())
        }
    }
}
