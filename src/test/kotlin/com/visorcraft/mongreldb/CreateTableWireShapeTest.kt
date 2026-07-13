package com.visorcraft.mongreldb

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/**
 * Wire-shape conformance tests for [MongrelDB.createTable] (T5.2).
 *
 * `createTable` forwards each column [Map] to the daemon verbatim, so any key a
 * caller places on a column descriptor - including the newer `enum_variants` and
 * `default_value` fields - must reach `/kit/create_table` unchanged, and keys
 * that are *not* set must never appear on the wire.
 *
 * These tests exercise the full transport end-to-end (payload assembly -> JSON
 * encoding -> HTTP body) against an in-process
 * [com.sun.net.httpserver.HttpServer] that captures the raw request body. This
 * mirrors the offline auth-header test in `MongrelDBLiveTest` and introduces no
 * external HTTP-mock dependency.
 */
class CreateTableWireShapeTest {
    @Test
    fun queryBuilderIncludesOffset() {
        val payload = QueryBuilder(db, "orders").limit(10).offset(12).build()
        assertEquals(10L, payload["limit"])
        assertEquals(12L, payload["offset"])
    }

    private lateinit var server: HttpServer
    private lateinit var db: MongrelDB
    private val lastBody = AtomicReference<String?>(null)

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/kit/create_table") { exchange ->
            lastBody.set(readAll(exchange.requestBody))
            val resp = """{"table_id":7}""".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, resp.size.toLong())
            exchange.responseBody.use { it.write(resp) }
        }
        server.start()
        db = MongrelDB("http://127.0.0.1:${server.address.port}")
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    @Test
    @DisplayName("createTable preserves enum, static-default, and dynamic-default fields")
    fun enumVariantsAndDefaultValueAppearVerbatim() {
        val statusCol =
            linkedMapOf<String, Any?>(
                "id" to 2L,
                "name" to "status",
                "ty" to "enum",
                "primary_key" to false,
                "nullable" to false,
                "enum_variants" to listOf("open", "closed", "archived"),
            )

        val createdAt =
            linkedMapOf<String, Any?>(
                "id" to 3L,
                "name" to "created_at",
                "ty" to "timestamp_nanos",
                "default_expr" to "now",
            )
        val attempts = mapOf<String, Any?>("id" to 4L, "name" to "attempts", "ty" to "int64", "default_value" to 3L)
        val extras = listOf(
            mapOf<String, Any?>("id" to 5L, "name" to "s", "ty" to "varchar", "default_value" to "draft"),
            mapOf<String, Any?>("id" to 6L, "name" to "b", "ty" to "bool", "default_value" to true),
            mapOf<String, Any?>("id" to 7L, "name" to "n", "ty" to "varchar", "default_value" to null),
        )
        val constraints =
            mapOf<String, Any?>(
                "checks" to listOf(
                    mapOf("id" to 1L, "name" to "id_present", "expr" to mapOf("IsNotNull" to 1L)),
                ),
            )

        val id = db.createTable("tickets", listOf(idColumn(), statusCol, createdAt, attempts) + extras, constraints)
        assertEquals(7L, id, "createTable should return the daemon's table_id")

        val body = lastBody.get()
        assertNotNull(body, "server captured no request body")

        // All optional keys must appear verbatim in the on-wire JSON.
        assertTrue(body!!.contains("\"enum_variants\""), "body missing enum_variants key: $body")
        assertTrue(body.contains("\"default_value\":3"), "body missing scalar default_value: $body")
        assertTrue(body.contains("\"default_expr\":\"now\""), "body missing default_expr: $body")
        assertTrue(body.contains("\"default_value\":\"draft\""), "body missing string default: $body")
        assertTrue(body.contains("\"default_value\":true"), "body missing bool default: $body")
        assertTrue(body.contains("\"default_value\":null"), "body missing null default: $body")

        // The values must round-trip too, not just the keys: the variant array
        // is serialized in order and the static default remains numeric.
        assertTrue(
            body.contains("[\"open\",\"closed\",\"archived\"]"),
            "enum_variants not serialized as an ordered array: $body",
        )
        assertTrue(
            body.contains("\"default_value\":3"),
            "default_value did not preserve its numeric type: $body",
        )
        assertTrue(body.contains("\"constraints\""), "body missing constraints key: $body")
        assertTrue(body.contains("\"checks\""), "body missing constraints.checks: $body")
        assertTrue(body.contains("\"IsNotNull\":1"), "body missing check expression: $body")
    }

    @Test
    @DisplayName("createTable omits enum_variants and default_value when unset (regression)")
    fun unsetKeysAreAbsentFromWire() {
        db.createTable("plain", listOf(idColumn()))

        val body = lastBody.get()
        assertNotNull(body, "server captured no request body")

        // A column that never set these keys must not leak them onto the wire.
        assertFalse(body!!.contains("enum_variants"), "unset enum_variants leaked into body: $body")
        assertFalse(body.contains("default_value"), "unset default_value leaked into body: $body")
    }

    @Test
    @DisplayName("createTable preserves the full static-default matrix as typed JSON")
    fun staticDefaultMatrixPreservesJsonTypes() {
        val columns =
            listOf(
                idColumn(),
                mapOf("id" to 2L, "name" to "s", "ty" to "varchar", "default_value" to "draft"),
                mapOf("id" to 3L, "name" to "i", "ty" to "int64", "default_value" to 7L),
                mapOf("id" to 4L, "name" to "b", "ty" to "bool", "default_value" to true),
                mapOf("id" to 5L, "name" to "n", "ty" to "varchar", "default_value" to null),
                mapOf("id" to 6L, "name" to "literal_now", "ty" to "varchar", "default_value" to "now"),
                mapOf("id" to 7L, "name" to "dyn_now", "ty" to "timestamp_nanos", "default_expr" to "now"),
                mapOf("id" to 8L, "name" to "dyn_uuid", "ty" to "uuid", "default_expr" to "uuid"),
            )

        db.createTable("defaults", columns)

        val body = lastBody.get()
        assertNotNull(body, "server captured no request body")

        @Suppress("UNCHECKED_CAST")
        val parsed = MongrelDB.Json.parse(body!!.toByteArray(StandardCharsets.UTF_8)) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val cols = parsed["columns"] as List<Map<String, Any?>>

        fun col(name: String): Map<String, Any?>? = cols.find { it["name"] == name }

        // Static defaults preserve their JSON types.
        assertEquals("draft", col("s")?.get("default_value"))
        assertEquals(7L, col("i")?.get("default_value"))
        assertEquals(true, col("b")?.get("default_value"))
        assertTrue(col("n")!!.containsKey("default_value") && col("n")!!["default_value"] == null,
            "explicit null default_value should be present and null")
        assertEquals("now", col("literal_now")?.get("default_value"),
            "literal \"now\" string must stay a static default_value, not become default_expr")

        // Dynamic defaults use default_expr and must not also emit default_value.
        assertEquals("now", col("dyn_now")?.get("default_expr"))
        assertFalse(col("dyn_now")!!.containsKey("default_value"),
            "default_expr column should not emit default_value")
        assertEquals("uuid", col("dyn_uuid")?.get("default_expr"))
        assertFalse(col("dyn_uuid")!!.containsKey("default_value"),
            "default_expr column should not emit default_value")
    }

    @Test
    @DisplayName("setHistoryRetentionEpochs sends PUT /history/retention with exact body and keys")
    fun setHistoryRetentionEpochsWire() {
        val captured = AtomicReference<Triple<String?, String?, String?>>(null)
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/history/retention") { exchange ->
            val body = readAll(exchange.requestBody)
            captured.set(Triple(exchange.requestMethod, exchange.requestURI.path, body))
            val resp =
                """{"history_retention_epochs":42,"earliest_retained_epoch":5}"""
                    .toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, resp.size.toLong())
            exchange.responseBody.use { it.write(resp) }
        }
        srv.start()
        try {
            val c = MongrelDB("http://127.0.0.1:${srv.address.port}")
            val result = c.setHistoryRetentionEpochs(42L)
            assertEquals(42L, result.historyRetentionEpochs)
            assertEquals(5L, result.earliestRetainedEpoch)

            val (method, path, reqBody) = captured.get()!!
            assertEquals("PUT", method)
            assertEquals("/history/retention", path)
            @Suppress("UNCHECKED_CAST")
            val parsed = MongrelDB.Json.parse(reqBody!!.toByteArray(StandardCharsets.UTF_8)) as Map<String, Any?>
            assertEquals(42L, parsed["history_retention_epochs"])
        } finally {
            srv.stop(0)
        }
    }

    @Test
    @DisplayName("historyRetentionEpochs sends GET /history/retention and returns the value")
    fun historyRetentionEpochsWire() {
        val captured = AtomicReference<Pair<String?, String?>>(null)
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/history/retention") { exchange ->
            captured.set(Pair(exchange.requestMethod, exchange.requestURI.path))
            val resp =
                """{"history_retention_epochs":7,"earliest_retained_epoch":3}"""
                    .toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, resp.size.toLong())
            exchange.responseBody.use { it.write(resp) }
        }
        srv.start()
        try {
            val c = MongrelDB("http://127.0.0.1:${srv.address.port}")
            assertEquals(7L, c.historyRetentionEpochs())
            val (method, path) = captured.get()!!
            assertEquals("GET", method)
            assertEquals("/history/retention", path)
        } finally {
            srv.stop(0)
        }
    }

    @Test
    @DisplayName("earliestRetainedEpoch sends GET /history/retention and returns the value")
    fun earliestRetainedEpochWire() {
        val captured = AtomicReference<Pair<String?, String?>>(null)
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/history/retention") { exchange ->
            captured.set(Pair(exchange.requestMethod, exchange.requestURI.path))
            val resp =
                """{"history_retention_epochs":9,"earliest_retained_epoch":4}"""
                    .toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, resp.size.toLong())
            exchange.responseBody.use { it.write(resp) }
        }
        srv.start()
        try {
            val c = MongrelDB("http://127.0.0.1:${srv.address.port}")
            assertEquals(4L, c.earliestRetainedEpoch())
            val (method, path) = captured.get()!!
            assertEquals("GET", method)
            assertEquals("/history/retention", path)
        } finally {
            srv.stop(0)
        }
    }

    @Test
    @DisplayName("retention endpoints propagate non-2xx responses as QueryException")
    fun retentionPropagatesNon2xx() {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/history/retention") { exchange ->
            val resp =
                """{"error":{"message":"service unavailable","code":"BUSY"}}"""
                    .toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(503, resp.size.toLong())
            exchange.responseBody.use { it.write(resp) }
        }
        srv.start()
        try {
            val c = MongrelDB("http://127.0.0.1:${srv.address.port}")
            assertThrows(QueryException::class.java) { c.setHistoryRetentionEpochs(10L) }
            assertThrows(QueryException::class.java) { c.historyRetentionEpochs() }
            assertThrows(QueryException::class.java) { c.earliestRetainedEpoch() }
        } finally {
            srv.stop(0)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun idColumn(): Map<String, Any?> =
        linkedMapOf(
            "id" to 1L,
            "name" to "id",
            "ty" to "int64",
            "primary_key" to true,
            "nullable" to false,
        )

    private fun readAll(stream: InputStream): String =
        stream.use { String(it.readBytes(), StandardCharsets.UTF_8) }
}
