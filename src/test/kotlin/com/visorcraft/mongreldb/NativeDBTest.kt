package com.visorcraft.mongreldb

import com.visorcraft.mongreldb.native_mode.NativeDB
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the native embedded mode (libmongreldb_jni via JNI).
 *
 * Self-skip when the native library cannot be loaded.
 */
class NativeDBTest {

    companion object {
        private val SCHEMA_JSON = """
            {
                "tables": [{
                    "id": 1,
                    "name": "users",
                    "columns": [
                        {"id":1,"name":"id","storage_type":"int64","application_type":"int64","nullable":false,"primary_key":true,"default":null,"generated":false},
                        {"id":2,"name":"name","storage_type":"text","application_type":"text","nullable":true,"primary_key":false,"default":null,"generated":false}
                    ],
                    "primary_key": ["id"]
                }]
            }
        """.trimIndent()

        private fun nativeAvailable(): Boolean = NativeDB.nativeAvailable()
    }

    @Test
    fun `create and SQL insert select`(@TempDir tempDir: Path) {
        assumeTrue(nativeAvailable(), "native library not available")

        NativeDB.create(tempDir.toString(), SCHEMA_JSON).use { db ->
            db.sqlRows("INSERT INTO users (id, name) VALUES (1, 'alice')")

            val rows = db.sqlRows("SELECT id, name FROM users")
            assertTrue(rows.contains("alice"), "rows should contain 'alice': $rows")
        }
    }

    @Test
    fun `SQL Arrow returns Arrow magic`(@TempDir tempDir: Path) {
        assumeTrue(nativeAvailable(), "native library not available")

        NativeDB.create(tempDir.toString(), SCHEMA_JSON).use { db ->
            db.sqlRows("INSERT INTO users (id, name) VALUES (1, 'bob')")

            val arrow = db.sqlArrow("SELECT id FROM users")
            assertTrue(arrow.size >= 6, "Arrow IPC should be at least 6 bytes")
            assertEquals("ARROW1", String(arrow, 0, 6), "should start with ARROW1 magic")
        }
    }

    @Test
    fun `migrate creates table and reads back`(@TempDir tempDir: Path) {
        assumeTrue(nativeAvailable(), "native library not available")

        NativeDB.create(tempDir.toString(), SCHEMA_JSON).use { db ->
            val migrations = """
                [{
                    "version": 1,
                    "name": "add_orders",
                    "ops": [{"raw_sql": "CREATE TABLE orders (id INT64 PRIMARY KEY, total FLOAT64)"}]
                }]
            """.trimIndent()
            db.migrate(migrations)

            db.sqlRows("INSERT INTO orders (id, total) VALUES (1, 99.99)")

            val applied = db.appliedMigrations()
            assertTrue(applied.contains("add_orders"), "should contain 'add_orders': $applied")
        }
    }

    @Test
    fun `query select returns rows`(@TempDir tempDir: Path) {
        assumeTrue(nativeAvailable(), "native library not available")

        NativeDB.create(tempDir.toString(), SCHEMA_JSON).use { db ->
            db.sqlRows("INSERT INTO users (id, name) VALUES (1, 'carol')")

            val selectJson = """
                {"table":"users","columns":[],"filter":null,"order_by":[],"limit":null,"offset":null}
            """.trimIndent()
            val result = db.querySelect(selectJson)
            assertTrue(result.contains("carol"), "should contain 'carol': $result")
        }
    }
}
