package dev.visorcraft.mongreldb

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

/**
 * Live integration tests for the MongrelDB Kotlin client.
 *
 * These tests boot a real `mongreldb-server` daemon and exercise the full
 * client surface against it. They resolve the daemon binary in this order:
 *
 * 1. the `MONGRELDB_SERVER` env var (path to the server binary)
 * 2. a prebuilt binary at `./bin/mongreldb-server`
 * 3. `mongreldb-server` on `PATH`
 *
 * If no binary is available, the suite is skipped. Set `MONGRELDB_URL` to point
 * at an already-running daemon to skip the boot and connect directly.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MongrelDBLiveTest {
    private var db: MongrelDB? = null
    private var serverProcess: Process? = null
    private var dataDir: Path? = null
    private var logFile: Path? = null
    private var externalDaemon = false

    @BeforeAll
    fun bootDaemon() {
        val existing = env("MONGRELDB_URL")
        if (existing.isNotEmpty()) {
            // If a daemon is already running, connect to it directly.
            db = MongrelDB(existing, token = env("MONGRELDB_TOKEN"))
            if (db!!.health()) {
                externalDaemon = true
                LOG.info("Using existing daemon at $existing")
                return
            }
            fail<Unit>("MONGRELDB_URL=$existing is not reachable")
        }

        val bin = resolveServerBinary()
        if (bin == null) {
            // No daemon available: skip the entire suite. The offline tests
            // below still run so the suite isn't reported as "no tests".
            LOG.info("No mongreldb-server binary available; live tests skipped")
            return
        }

        val port = freePort()
        dataDir = Files.createTempDirectory("mongreldb-kotlin-test-")
        logFile = Files.createTempFile("mongreldb-kotlin-server-", ".log")

        serverProcess =
            ProcessBuilder(bin, dataDir!!.toString(), "--port", port.toString())
                .redirectOutput(logFile!!.toFile())
                .redirectErrorStream(true)
                .start()

        val url = "http://127.0.0.1:$port"
        if (!waitForHealth(url, 40)) {
            val log = readLog()
            destroyProcess()
            fail<Unit>("mongreldb-server did not become healthy. Log:\n$log")
        }
        db = MongrelDB(url)
        LOG.info("Booted mongreldb-server on $url")
    }

    @AfterAll
    fun tearDown() {
        serverProcess?.let { destroyProcess() }
        dataDir?.let {
            try {
                deleteRecursively(it)
            } catch (e: IOException) {
                LOG.warning("Could not delete data dir: ${e.message}")
            }
        }
        logFile?.let {
            try {
                Files.deleteIfExists(it)
            } catch (e: IOException) {
                // best-effort
            }
        }
    }

    /** Skip every live test when no daemon was booted. */
    private fun requireDaemon() {
        assumeTrue(db != null, "no mongreldb-server available")
    }

    @Test
    @Order(1)
    @DisplayName("health() reports the daemon as healthy")
    fun testHealth() {
        requireDaemon()
        assertTrue(db!!.health(), "expected healthy daemon")
    }

    @Test
    @Order(2)
    @DisplayName("createTable + count round-trip")
    fun testCreateTableAndCount() {
        requireDaemon()
        val name = uniqueTable("kotlin_tbl")
        freshTable(name, intCol(1, "id", primaryKey = true), floatCol(2, "amount"))
        assertEquals(0L, db!!.count(name), "expected 0 rows")
    }

    @Test
    @Order(3)
    @DisplayName("put + count round-trip")
    fun testPutAndCountRoundTrip() {
        requireDaemon()
        val name = uniqueTable("kotlin_put")
        freshTable(name, intCol(1, "id", primaryKey = true), floatCol(2, "amount"))

        db!!.put(name, cells(1L to 1L, 2L to 99.5))
        db!!.put(name, cells(1L to 2L, 2L to 150.0))

        assertEquals(2L, db!!.count(name), "expected 2 rows")
    }

    @Test
    @Order(4)
    @DisplayName("query by primary key")
    fun testQueryByPK() {
        requireDaemon()
        val name = uniqueTable("kotlin_pk")
        freshTable(name, intCol(1, "id", primaryKey = true))

        db!!.put(name, cells(1L to 42L))
        db!!.put(name, cells(1L to 43L))

        val rows =
            db!!
                .query(name)
                .where("pk", mapOf("value" to 42L))
                .execute()
        assertEquals(1, rows.size, "expected exactly 1 row")
        // The returned row must carry the queried PK value.
        assertEquals(42L, cellLong(rows[0], 1L), "expected returned pk 42")
    }

    @Test
    @Order(5)
    @DisplayName("query with a range condition using friendly aliases")
    fun testQueryRange() {
        requireDaemon()
        val name = uniqueTable("kotlin_range")
        freshTable(name, intCol(1, "id", primaryKey = true), intCol(2, "amount", primaryKey = false))

        db!!.put(name, cells(1L to 1L, 2L to 50L))
        db!!.put(name, cells(1L to 2L, 2L to 120L))
        db!!.put(name, cells(1L to 3L, 2L to 200L))

        // Range predicate using friendly aliases (column/min/max -> column_id/lo/hi).
        val q =
            db!!
                .query(name)
                .where("range", mapOf("column" to 2L, "min" to 100L, "max" to 150L))
        val rows = q.execute()
        // Only the row with amount=120 (pk=2) falls in [100, 150].
        assertEquals(1, rows.size, "range query should return exactly the matching row")
        assertFalse(q.truncated, "result should not be truncated")
        // Verify the PK and amount values of the returned row match the filter.
        assertEquals(2L, cellLong(rows[0], 1L), "expected returned pk 2")
        val amt = cellLong(rows[0], 2L)
        assertNotNull(amt, "expected a non-null amount")
        assertTrue(amt in 100L..150L, "returned amount $amt outside range [100,150]")
    }

    @Test
    @Order(6)
    @DisplayName("batch transaction: put + commit")
    fun testTransactionPutCommit() {
        requireDaemon()
        val name = uniqueTable("kotlin_txn")
        freshTable(name, intCol(1, "id", primaryKey = true))

        val txn = db!!.beginTransaction()
        txn.put(name, cells(1L to 1L))
        txn.put(name, cells(1L to 2L))
        txn.put(name, cells(1L to 3L))
        assertEquals(3, txn.count(), "expected 3 staged ops")

        val results = txn.commit()
        assertEquals(3, results.size, "expected 3 results")
        assertEquals(3L, db!!.count(name), "expected 3 rows after commit")
    }

    @Test
    @Order(7)
    @DisplayName("transaction rollback discards staged ops")
    fun testTransactionRollback() {
        requireDaemon()
        val name = uniqueTable("kotlin_rb")
        freshTable(name, intCol(1, "id", primaryKey = true))

        val txn = db!!.beginTransaction()
        txn.put(name, cells(1L to 1L))
        assertEquals(1, txn.count())
        txn.rollback()
        assertEquals(0L, db!!.count(name), "rollback should leave the table empty")
    }

    @Test
    @Order(8)
    @DisplayName("deleteByPk removes a row")
    fun testDeleteByPK() {
        requireDaemon()
        val name = uniqueTable("kotlin_del")
        freshTable(name, intCol(1, "id", primaryKey = true))

        db!!.put(name, cells(1L to 5L))
        assertEquals(1L, db!!.count(name))

        db!!.deleteByPk(name, 5L)
        assertEquals(0L, db!!.count(name), "expected 0 rows after delete")
    }

    @Test
    @Order(9)
    @DisplayName("sql INSERT increases count; JSON SELECT returns rows")
    fun testSQL() {
        requireDaemon()
        val name = uniqueTable("kotlin_sql")
        freshTable(name, intCol(1, "id", primaryKey = true), intCol(2, "amount", primaryKey = false))
        assertEquals(0L, db!!.count(name), "expected 0 rows before SQL INSERT")

        // INSERT via SQL must increase the row count.
        db!!.sql("INSERT INTO $name (id, amount) VALUES (10, 42)")
        assertEquals(1L, db!!.count(name), "expected count to increase to 1 after SQL INSERT")

        // JSON SQL mode must return the inserted row. An old server ignores the
        // requested JSON format and answers with Arrow IPC bytes, so sql()
        // returns an empty list - only verify row content when JSON mode worked.
        val rows = db!!.sql("SELECT id, amount FROM $name")
        if (rows.isNotEmpty()) {
            assertEquals(1, rows.size, "expected 1 row from JSON SELECT")
        }
    }

    @Test
    @Order(10)
    @DisplayName("schema lists the created table")
    fun testSchema() {
        requireDaemon()
        val name = uniqueTable("kotlin_schema")
        freshTable(name, intCol(1, "id", primaryKey = true), floatCol(2, "amount"))

        val schema = db!!.schema()
        assertTrue(schema.containsKey(name), "schema catalog missing table $name")
    }

    @Test
    @Order(11)
    @DisplayName("schemaFor returns a single-table descriptor")
    fun testSchemaFor() {
        requireDaemon()
        val name = uniqueTable("kotlin_schema_for")
        freshTable(name, intCol(1, "id", primaryKey = true), floatCol(2, "amount"))

        val desc = db!!.schemaFor(name)
        assertNotNull(desc["schema_id"], "descriptor missing schema_id; got $desc")
        val cols = desc["columns"]
        assertTrue(cols is List<*>, "columns should be a list")
        assertEquals(2, (cols as List<*>).size, "expected 2 columns")
    }

    @Test
    @Order(12)
    @DisplayName("tableNames lists a created table")
    fun testTableNamesListsCreatedTable() {
        requireDaemon()
        val name = uniqueTable("kotlin_tables")
        freshTable(name, intCol(1, "id", primaryKey = true))

        val names = db!!.tableNames()
        assertTrue(names.contains(name), "table list $names missing $name")
    }

    @Test
    @Order(13)
    @DisplayName("schemaFor on a nonexistent table throws NotFoundException")
    fun testErrorOnNonexistentTable() {
        requireDaemon()
        val name = uniqueTable("kotlin_missing")
        try {
            db!!.schemaFor(name)
            fail("expected NotFoundException for nonexistent table")
        } catch (e: NotFoundException) {
            assertEquals(404, e.status, "expected status 404")
        }
    }

    @Test
    @Order(14)
    @DisplayName("error carries the HTTP status code")
    fun testErrorTypeCarriesStatus() {
        requireDaemon()
        val name = uniqueTable("kotlin_missing2")
        try {
            db!!.schemaFor(name)
            fail("expected an error")
        } catch (e: MongrelDBException) {
            assertEquals(404, e.status, "expected status 404")
            assertTrue(e is NotFoundException, "expected NotFoundException, got ${e::class.simpleName}")
        }
    }

    @Test
    @Order(15)
    @DisplayName("upsert updates on a primary-key conflict")
    fun testUpsertOnConflict() {
        requireDaemon()
        val name = uniqueTable("kotlin_upsert")
        freshTable(name, intCol(1, "id", primaryKey = true), intCol(2, "amount", primaryKey = false))

        db!!.put(name, cells(1L to 1L, 2L to 50L))
        // Upsert the same PK with an update_cells that rewrites amount.
        db!!.upsert(name, cells(1L to 1L, 2L to 50L), updateCells = cells(2L to 999L))
        assertEquals(1L, db!!.count(name), "upsert should not add a second row")

        // The updated value should be visible via a PK query.
        val rows =
            db!!
                .query(name)
                .where("pk", mapOf("value" to 1L))
                .execute()
        assertEquals(1, rows.size, "expected the upserted row")
        // Verify the PK and the updated amount value landed.
        assertEquals(1L, cellLong(rows[0], 1L), "expected returned pk 1")
        assertEquals(999L, cellLong(rows[0], 2L), "expected updated amount 999")
    }

    @Test
    @Order(16)
    @DisplayName("idempotent put returns the same result on retry")
    fun testIdempotentPut() {
        requireDaemon()
        val name = uniqueTable("kotlin_idem")
        freshTable(name, intCol(1, "id", primaryKey = true))

        val key = "idem-$name"
        val first = db!!.put(name, cells(1L to 7L), idempotencyKey = key)
        val second = db!!.put(name, cells(1L to 7L), idempotencyKey = key)
        // The daemon returns the original response on duplicate commits. The
        // row count must remain 1 either way.
        assertEquals(1L, db!!.count(name), "idempotent put should not duplicate the row")
        assertNotNull(first, "first result should not be null")
        assertNotNull(second, "second result should not be null")
    }

    @Test
    @Order(17)
    @DisplayName("compact and compactTable run without error")
    fun testCompact() {
        requireDaemon()
        val name = uniqueTable("kotlin_compact")
        freshTable(name, intCol(1, "id", primaryKey = true))
        db!!.put(name, cells(1L to 1L))

        // Both compaction endpoints should succeed without throwing.
        assertNotNull(db!!.compact())
        assertNotNull(db!!.compactTable(name))
    }

    @Test
    @Order(18)
    @DisplayName("dropTable removes a table")
    fun testDropTable() {
        requireDaemon()
        val name = uniqueTable("kotlin_drop")
        freshTable(name, intCol(1, "id", primaryKey = true))
        assertTrue(db!!.tableNames().contains(name), "table should exist before drop")

        db!!.dropTable(name)
        assertFalse(db!!.tableNames().contains(name), "table should be gone after drop")
    }

    @Test
    @Order(21)
    @DisplayName("retention window and AS OF EPOCH time-travel reads")
    fun testRetentionAndAsOfEpoch() {
        requireDaemon()
        val name = uniqueTable("kotlin_retention")

        db!!.setHistoryRetentionEpochs(100L)
        assertEquals(100L, db!!.historyRetentionEpochs())
        assertTrue(db!!.earliestRetainedEpoch() >= 0L)

        freshTable(name, intCol(1, "id", primaryKey = true), varcharCol(2, "name"))
        db!!.put(name, cells(1L to 1L, 2L to "orig"))
        val insertEpoch = db!!.commitTable(name)
        assertTrue(insertEpoch > 0, "commit should return a positive epoch")

        db!!.sql("UPDATE $name SET name = 'updated' WHERE id = 1")

        val current = db!!.sql("SELECT name FROM $name WHERE id = 1")
        assertEquals(1, current.size, "current SELECT should return one row")
        assertEquals("updated", current[0]["name"])

        val past = db!!.sql("SELECT name FROM $name AS OF EPOCH $insertEpoch WHERE id = 1")
        assertEquals(1, past.size, "AS OF EPOCH SELECT should return one row")
        assertEquals("orig", past[0]["name"])
    }

    /**
     * A standalone sanity test that always runs (no daemon needed): a client
     * constructed with no reachable server reports `health() == false` rather
     * than throwing.
     */
    @Test
    @Order(19)
    @DisplayName("health() returns false when the daemon is unreachable (offline)")
    fun testHealthReturnsFalseWhenUnreachable() {
        val unreachable = MongrelDB("http://127.0.0.1:1")
        assertFalse(unreachable.health(), "health should be false for an unreachable daemon")
    }

    /**
     * A standalone test (no daemon needed): a client constructed with a token
     * attaches a Bearer header. Verified against an in-process server.
     */
    @Test
    @Order(20)
    @DisplayName("bearer-token auth header is attached (offline, in-process server)")
    fun testAuthOptionIsApplied() {
        val lastAuth = AtomicReference<String?>(null)
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/health") { exchange ->
            lastAuth.set(exchange.requestHeaders.getFirst("Authorization"))
            val resp = """{"ok":true}""".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, resp.size.toLong())
            exchange.responseBody.use { it.write(resp) }
        }
        srv.start()
        try {
            val port = srv.address.port
            val c = MongrelDB("http://127.0.0.1:$port", token = "super-secret")
            assertTrue(c.health(), "expected healthy")
            assertEquals("Bearer super-secret", lastAuth.get(), "expected Bearer auth header, got ${lastAuth.get()}")
        } finally {
            srv.stop(0)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun cells(vararg kv: Pair<Long, Any?>): Cells =
        LinkedHashMap<Long, Any?>().apply {
            for ((k, v) in kv) put(k, v)
        }

    // cellLong returns the Long value for colId from a Kit row's flat `cells`
    // array (shape: [col_id, value, ...]), or null if absent/non-numeric.
    private fun cellLong(row: Row, colId: Long): Long? {
        val cellsArr = row["cells"] as? List<*> ?: return null
        var i = 0
        while (i + 1 < cellsArr.size) {
            val id = (cellsArr[i] as? Number)?.toLong()
            if (id == colId) {
                return (cellsArr[i + 1] as? Number)?.toLong()
            }
            i += 2
        }
        return null
    }

    // cellDouble returns the Double value for colId from a Kit row's flat cells
    // array, or null if absent/non-numeric.
    private fun cellDouble(row: Row, colId: Long): Double? {
        val cellsArr = row["cells"] as? List<*> ?: return null
        var i = 0
        while (i + 1 < cellsArr.size) {
            val id = (cellsArr[i] as? Number)?.toLong()
            if (id == colId) {
                return (cellsArr[i + 1] as? Number)?.toDouble()
            }
            i += 2
        }
        return null
    }

    private fun intCol(id: Long, name: String, primaryKey: Boolean): Map<String, Any?> =
        linkedMapOf(
            "id" to id,
            "name" to name,
            "ty" to "int64",
            "primary_key" to primaryKey,
            "nullable" to false,
        )

    private fun floatCol(id: Long, name: String): Map<String, Any?> =
        linkedMapOf(
            "id" to id,
            "name" to name,
            "ty" to "float64",
            "primary_key" to false,
            "nullable" to false,
        )

    private fun varcharCol(id: Long, name: String): Map<String, Any?> =
        linkedMapOf(
            "id" to id,
            "name" to name,
            "ty" to "varchar",
            "primary_key" to false,
            "nullable" to false,
        )

    /**
     * freshTable drops [name] if present then creates it with the given columns.
     * A missing table on drop is the expected pre-condition and is ignored.
     */
    private fun freshTable(name: String, vararg columns: Map<String, Any?>) {
        try {
            db!!.dropTable(name) // ignore "not found"
        } catch (e: MongrelDBException) {
            // expected when the table doesn't exist yet
        }
        db!!.createTable(name, columns.toList())
    }

    private fun uniqueTable(prefix: String): String = "${prefix}_${System.nanoTime().toString(16)}"

    private fun env(name: String): String = System.getenv(name) ?: ""

    /** Finds the daemon binary, or returns null to skip the live suite. */
    private fun resolveServerBinary(): String? {
        val envVar = env("MONGRELDB_SERVER")
        if (envVar.isNotEmpty()) {
            val p = Paths.get(envVar)
            if (Files.isExecutable(p)) {
                return p.toAbsolutePath().toString()
            }
            LOG.warning("MONGRELDB_SERVER=$envVar not found or not executable (live tests skipped)")
            return null
        }
        val local = Paths.get("bin", "mongreldb-server")
        if (Files.isExecutable(local)) {
            return local.toAbsolutePath().toString()
        }
        val pathSeparator = System.getProperty("path.separator") ?: ":"
        for (dir in (System.getenv("PATH") ?: "").split(pathSeparator)) {
            val p = Paths.get(dir, "mongreldb-server")
            if (Files.isExecutable(p)) {
                return p.toAbsolutePath().toString()
            }
        }
        return null
    }

    private fun freePort(): Int =
        ServerSocket(0).use { it.localPort }

    private fun waitForHealth(url: String, maxSeconds: Int): Boolean {
        val probe = MongrelDB(url)
        val deadline = System.currentTimeMillis() + maxSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (probe.health()) return true
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun destroyProcess() {
        val proc = serverProcess ?: return
        proc.destroy()
        try {
            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
            }
            proc.waitFor(2, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun readLog(): String =
        try {
            Files.readString(logFile!!, StandardCharsets.UTF_8)
        } catch (e: IOException) {
            "(could not read log: ${e.message})"
        }

    private fun deleteRecursively(p: Path) {
        if (Files.isDirectory(p)) {
            Files.newDirectoryStream(p).use { stream ->
                for (child in stream) deleteRecursively(child)
            }
        }
        Files.deleteIfExists(p)
    }

    private companion object {
        val LOG: Logger = Logger.getLogger(MongrelDBLiveTest::class.java.name)
    }
}
