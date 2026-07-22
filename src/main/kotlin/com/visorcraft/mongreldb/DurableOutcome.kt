package com.visorcraft.mongreldb

/**
 * Nested durable recovery payload on query status / cancel responses
 * (parity with the server `DurableOutcome` / `outcome` JSON object).
 */
public data class DurableOutcome(
    public val committed: Boolean? = null,
    public val committedStatements: Int? = null,
    public val lastCommitEpoch: Long? = null,
    public val lastCommitEpochText: String? = null,
    public val lastCommitHlc: CommitHlc? = null,
    public val firstCommitStatementIndex: Int? = null,
    public val lastCommitStatementIndex: Int? = null,
    public val completedStatements: Int? = null,
    public val statementIndex: Int? = null,
    public val serialization: String = "",
    public val serializationState: String? = null,
    public val terminalState: String? = null,
) {
    public companion object {
        /** Decode an `outcome` / `durable` object from a JSON map. */
        @JvmStatic
        public fun fromMap(raw: Any?): DurableOutcome {
            if (raw !is Map<*, *>) {
                return DurableOutcome()
            }
            return DurableOutcome(
                committed = raw["committed"] as? Boolean,
                committedStatements = asInt(raw["committed_statements"]),
                lastCommitEpoch = asLong(raw["last_commit_epoch"]),
                lastCommitEpochText =
                    raw["last_commit_epoch_text"]?.toString(),
                lastCommitHlc = CommitHlc.fromMap(raw["last_commit_hlc"]),
                firstCommitStatementIndex = asInt(raw["first_commit_statement_index"]),
                lastCommitStatementIndex = asInt(raw["last_commit_statement_index"]),
                completedStatements = asInt(raw["completed_statements"]),
                statementIndex = asInt(raw["statement_index"]),
                serialization = raw["serialization"]?.toString() ?: "",
                serializationState = raw["serialization_state"]?.toString(),
                terminalState = raw["terminal_state"]?.toString(),
            )
        }

        private fun asInt(v: Any?): Int? = (v as? Number)?.toInt()

        private fun asLong(v: Any?): Long? = (v as? Number)?.toLong()
    }
}
