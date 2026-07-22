package com.visorcraft.mongreldb

/**
 * Decoded `GET /queries/{query_id}` status for durable recovery (0.64+).
 *
 * Prefer [commitHlc] / [serializationState] helpers — they pick the nested
 * durable / outcome fields over top-level duplicates when present.
 */
public data class QueryStatus(
    public val queryId: String = "",
    public val status: String = "",
    public val state: String = "",
    public val serverState: String = "",
    public val terminalState: String? = null,
    public val committed: Boolean? = null,
    public val outcome: DurableOutcome = DurableOutcome(),
    public val durable: DurableOutcome? = null,
    public val lastCommitHlc: CommitHlc? = null,
    public val raw: Map<String, Any?> = emptyMap(),
) {
    /**
     * Authoritative commit HLC: nested `durable` → nested `outcome` → top-level
     * `last_commit_hlc`.
     */
    public fun commitHlc(): CommitHlc? {
        durable?.lastCommitHlc?.let { return it }
        outcome.lastCommitHlc?.let { return it }
        return lastCommitHlc
    }

    /**
     * Authoritative serialization state: nested durable/outcome
     * `serialization_state`, then `serialization`.
     */
    public fun serializationState(): String {
        durable?.let { d ->
            if (!d.serializationState.isNullOrEmpty()) return d.serializationState
            if (d.serialization.isNotEmpty()) return d.serialization
        }
        if (!outcome.serializationState.isNullOrEmpty()) {
            return outcome.serializationState
        }
        return outcome.serialization
    }

    public companion object {
        /** Decode a query-status JSON object map. */
        @JvmStatic
        @Suppress("UNCHECKED_CAST")
        public fun fromMap(raw: Map<String, Any?>?): QueryStatus {
            val map = raw ?: emptyMap()
            val outcome = DurableOutcome.fromMap(map["outcome"])
            val durable =
                if (map["durable"] is Map<*, *>) {
                    DurableOutcome.fromMap(map["durable"])
                } else {
                    null
                }
            val serverState =
                map["server_state"]?.toString()
                    ?: map["state"]?.toString()
                    ?: ""
            return QueryStatus(
                queryId = map["query_id"]?.toString() ?: "",
                status = map["status"]?.toString() ?: "",
                state = map["state"]?.toString() ?: "",
                serverState = serverState,
                terminalState = map["terminal_state"]?.toString(),
                committed = map["committed"] as? Boolean,
                outcome = outcome,
                durable = durable,
                lastCommitHlc = CommitHlc.fromMap(map["last_commit_hlc"]),
                raw = map,
            )
        }
    }
}
