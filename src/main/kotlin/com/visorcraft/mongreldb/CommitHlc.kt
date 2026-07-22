package com.visorcraft.mongreldb

/**
 * Structural hybrid logical clock from durable recovery (0.64+).
 *
 * Parsed from the nested `last_commit_hlc` object on query status / cancel
 * responses — never reconstructed by string-parsing free-form status text.
 */
public data class CommitHlc(
    public val physicalMicros: Long,
    public val logical: Int,
    public val nodeTiebreaker: Int,
) {
    public companion object {
        /** Decode a `last_commit_hlc` map; returns `null` when the shape is absent. */
        @JvmStatic
        public fun fromMap(raw: Any?): CommitHlc? {
            if (raw !is Map<*, *>) return null
            val phys = raw["physical_micros"] as? Number ?: return null
            val logical = (raw["logical"] as? Number)?.toInt() ?: 0
            val node = (raw["node_tiebreaker"] as? Number)?.toInt() ?: 0
            return CommitHlc(phys.toLong(), logical, node)
        }
    }
}
