package com.visorcraft.mongreldb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Offline unit tests for 0.64 durable HLC recovery parsers and multi-retriever
 * [SearchBuilder] payload assembly. No live daemon required.
 */
class DurableRetrieveTest {
    @Test
    fun queryStatusParsesStructuralHlcWithoutStringParsing() {
        val hlc =
            mapOf(
                "physical_micros" to 1_700_000_000_000_000L,
                "logical" to 3,
                "node_tiebreaker" to 7,
            )
        val outcome =
            mapOf(
                "committed" to true,
                "committed_statements" to 1,
                "last_commit_epoch" to 17,
                "last_commit_epoch_text" to "17",
                "last_commit_hlc" to hlc,
                "first_commit_statement_index" to 0,
                "last_commit_statement_index" to 0,
                "completed_statements" to 1,
                "statement_index" to 0,
                "serialization" to "succeeded",
                "serialization_state" to "succeeded",
            )
        val fixture =
            mapOf(
                "query_id" to "abcdefabcdefabcdefabcdefabcdefab",
                "status" to "committed",
                "state" to "completed",
                "server_state" to "completed",
                "terminal_state" to "committed",
                "committed" to true,
                "last_commit_epoch" to 17,
                "last_commit_hlc" to hlc,
                "outcome" to outcome,
                "durable" to outcome,
            )

        val status = QueryStatus.fromMap(fixture)
        assertEquals(true, status.committed)
        val parsed = status.commitHlc()
        assertNotNull(parsed)
        assertEquals(1_700_000_000_000_000L, parsed!!.physicalMicros)
        assertEquals(3, parsed.logical)
        assertEquals(7, parsed.nodeTiebreaker)
        assertEquals("succeeded", status.serializationState())
        assertEquals(17L, status.outcome.lastCommitEpoch)
    }

    @Test
    fun multiRetrieverSearchBuildIncludesTwoRetrieversAndFusion() {
        val client = MongrelDB("http://127.0.0.1:9")
        val payload =
            client
                .search("docs")
                .annRetriever("ann", 3L, listOf(0.1, 0.2), 10, 1.0)
                .sparseRetriever("sparse", 4L, listOf(listOf(1, 0.5)), 10, 0.5)
                .fusion(60)
                .limit(5)
                .build()
        @Suppress("UNCHECKED_CAST")
        val retrievers = payload["retrievers"] as List<Map<String, Any?>>
        assertEquals(2, retrievers.size)
        assertTrue(payload.containsKey("fusion"))
    }

    @Test
    fun retrieveTextRequestShape() {
        // Build the same payload shape retrieveText sends (offline).
        val payload =
            linkedMapOf<String, Any?>(
                "table" to "docs",
                "embedding_column" to 3,
                "text" to "cat sat",
                "k" to 5,
            )
        val bytes = MongrelDB.Json.toBytes(payload)
        @Suppress("UNCHECKED_CAST")
        val decoded = MongrelDB.Json.parse(bytes) as Map<String, Any?>
        assertEquals("docs", decoded["table"])
        assertEquals(3L, decoded["embedding_column"])
        assertEquals("cat sat", decoded["text"])
        assertEquals(5L, decoded["k"])
    }

    @Test
    fun commitHlcFromMapRejectsMissingPhysical() {
        assertEquals(null, CommitHlc.fromMap(mapOf("logical" to 1)))
        assertEquals(null, CommitHlc.fromMap(null))
        assertEquals(null, CommitHlc.fromMap("not-a-map"))
    }
}
