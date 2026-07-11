// Example: basic CRUD with the native embedded MongrelDB engine (Tier 1).
//
// Unlike basic_crud.kt which connects to a daemon over HTTP, this example
// runs the engine in-process via JNI. No daemon needed.
//
// Run (from the repo root, with the client on the classpath):
//
//   export MONGRELDB_NATIVE_DIR=/path/to/dir/with/libmongreldb_jni.so
//   kotlin -cp mongreldb-kotlin.jar examples/native_basic_crud.kt
//
// Download the native library from
// https://github.com/visorcraft/MongrelDB/releases (mongreldb-jni JAR or
// mongreldb-native archives).

package com.example

import dev.visorcraft.mongreldb.native_mode.NativeDB

fun main() {
    if (!NativeDB.nativeAvailable()) {
        System.err.println("Native library not available.")
        System.err.println("Set MONGRELDB_NATIVE_DIR to the directory containing")
        System.err.println("libmongreldb_jni.so (Linux), .dylib (macOS), or .dll (Windows).")
        System.err.println("Download from https://github.com/visorcraft/MongrelDB/releases")
        return
    }

    val dbDir = System.getProperty("java.io.tmpdir") + "/mdb_native_example_" + System.currentTimeMillis()
    val schemaJson = """
        {"tables":[{"id":1,"name":"users",
        "columns":[
        {"id":1,"name":"id","storage_type":"int64","application_type":"int64","nullable":false,"primary_key":true,"default":null,"generated":false},
        {"id":2,"name":"name","storage_type":"text","application_type":"text","nullable":true,"primary_key":false,"default":null,"generated":false},
        {"id":3,"name":"email","storage_type":"text","application_type":"text","nullable":true,"primary_key":false,"default":null,"generated":false}
        ],"primary_key":["id"]}]}
    """.trimIndent()

    println("=== Native Embedded Basic CRUD ===")
    println("Database dir: $dbDir")
    println()

    NativeDB.create(dbDir, schemaJson).use { db ->
        println("1. Database created with schema (users table)")

        // Insert rows via SQL.
        db.sqlRows("INSERT INTO users (id, name, email) VALUES (1, 'Alice', 'alice@example.com')")
        db.sqlRows("INSERT INTO users (id, name, email) VALUES (2, 'Bob', 'bob@example.com')")
        db.sqlRows("INSERT INTO users (id, name, email) VALUES (3, 'Carol', 'carol@example.com')")
        println("2. Inserted 3 rows via SQL")

        // SELECT via SQL (JSON rows).
        val rows = db.sqlRows("SELECT id, name, email FROM users ORDER BY id")
        println("3. SELECT all rows:")
        println("   $rows")

        // Arrow IPC for columnar reads.
        val arrow = db.sqlArrow("SELECT id FROM users")
        println("4. Arrow IPC: ${arrow.size} bytes")

        // Migration: add an orders table.
        val migrations = """
            [{"version":1,"name":"add_orders",
            "ops":[{"raw_sql":"CREATE TABLE orders (id INT64 PRIMARY KEY, user_id INT64, total FLOAT64)"}]}]
        """.trimIndent()
        db.migrate(migrations)
        println("5. Migration: created 'orders' table")

        // Insert into the migrated table.
        db.sqlRows("INSERT INTO orders (id, user_id, total) VALUES (1, 1, 99.99)")
        db.sqlRows("INSERT INTO orders (id, user_id, total) VALUES (2, 2, 49.99)")

        // SQL JOIN across both tables.
        val joinResult = db.sqlRows(
            "SELECT u.name, o.total FROM users u " +
            "JOIN orders o ON u.id = o.user_id ORDER BY o.total DESC")
        println("6. SQL JOIN (users + orders):")
        println("   $joinResult")

        // Kit query builder: SELECT with filter.
        val selectJson = """
            {"table":"users","columns":[],"filter":null,"order_by":[],"limit":null,"offset":null}
        """.trimIndent()
        val queryResult = db.querySelect(selectJson)
        println("7. Kit query builder SELECT:")
        println("   $queryResult")

        // Read back applied migrations.
        val applied = db.appliedMigrations()
        println("8. Applied migrations: $applied")

        println()
        println("=== All operations completed successfully! ===")
    }
}
