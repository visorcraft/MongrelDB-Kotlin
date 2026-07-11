package dev.visorcraft.mongreldb

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
    @DisplayName("createTable sends enum_variants and default_value verbatim")
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
                "default_value" to "now",
            )
        val constraints =
            mapOf<String, Any?>(
                "checks" to listOf(
                    mapOf("id" to 1L, "name" to "id_present", "expr" to mapOf("IsNotNull" to 1L)),
                ),
            )

        val id = db.createTable("tickets", listOf(idColumn(), statusCol, createdAt), constraints)
        assertEquals(7L, id, "createTable should return the daemon's table_id")

        val body = lastBody.get()
        assertNotNull(body, "server captured no request body")

        // Both keys must appear verbatim in the on-wire JSON.
        assertTrue(body!!.contains("\"enum_variants\""), "body missing enum_variants key: $body")
        assertTrue(body.contains("\"default_value\""), "body missing default_value key: $body")

        // The values must round-trip too, not just the keys: the variant array
        // is serialized in order and the default is the string "open".
        assertTrue(
            body.contains("[\"open\",\"closed\",\"archived\"]"),
            "enum_variants not serialized as an ordered array: $body",
        )
        assertTrue(
            body.contains("\"default_value\":\"now\""),
            "default_value not serialized verbatim: $body",
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
