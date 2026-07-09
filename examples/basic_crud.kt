// Example: basic CRUD operations with the MongrelDB Kotlin client.
//
// Run (from the repo root, with the client on the classpath):
//
//   kotlin -cp mongreldb-kotlin.jar examples/basic_crud.kt
//
// or as a Gradle/Maven app with `dev.visorcraft:mongreldb-kotlin` on the
// classpath and this file under src/main/kotlin.
//
// Requires a mongreldb-server daemon running on http://127.0.0.1:8453.
//
// Creates a table, inserts three rows, counts them, queries all rows,
// upserts (updates) one row by primary key, deletes one row, then drops
// the table. Progress is printed at every step.

package com.example

import dev.visorcraft.mongreldb.MongrelDB

private const val DB_URL = "http://127.0.0.1:8453"
private const val TABLE = "example_crud"

// Column schema shared across all examples:
//   col 1 = id (int64, primary key)
//   col 2 = name (varchar)
//   col 3 = score (float64)
private fun column(id: Long, name: String, ty: String, primaryKey: Boolean): Map<String, Any?> =
    mapOf(
        "id" to id,
        "name" to name,
        "ty" to ty,
        "primary_key" to primaryKey,
        "nullable" to false,
    )

private fun row(id: Long, name: String, score: Double): Map<Long, Any?> =
    mapOf(1L to id, 2L to name, 3L to score)

private fun printResult(label: String, rows: List<Map<Long, Any?>>) {
    println("  $label: ${rows.size} rows")
    for (r in rows) {
        println("    { ${r.entries.joinToString(", ") { (k, v) -> "col$k=$v" }} }")
    }
}

fun main() {
    val db = MongrelDB(DB_URL)

    // 1. Health check; bail out if the daemon is unreachable.
    if (!db.health()) {
        System.err.println("daemon not reachable at $DB_URL")
        return
    }
    println("Connected to MongrelDB")

    // 2. Create the table.
    val tableId = db.createTable(
        TABLE,
        listOf(
            column(1L, "id", "int64", primaryKey = true),
            column(2L, "name", "varchar", primaryKey = false),
            column(3L, "score", "float64", primaryKey = false),
        ),
    )
    println("Created table $TABLE (id $tableId)")

    // 3. Insert three rows.
    db.put(TABLE, row(1L, "Alice", 95.5))
    db.put(TABLE, row(2L, "Bob", 82.0))
    db.put(TABLE, row(3L, "Carol", 78.3))
    println("Inserted 3 rows")

    // 4. Count.
    println("Total rows: ${db.count(TABLE)}")

    // 5. Query all rows (no conditions, no projection, no limit).
    val allRows = db.query(TABLE).execute()
    printResult("all rows", allRows)

    // 6. Upsert (update) Alice's score. updateCells supplies the values
    //    written on a primary-key conflict.
    db.upsert(
        TABLE,
        row(1L, "Alice", 100.0),
        updateCells = mapOf(2L to "Alice", 3L to 100.0),
    )
    println("Upserted Alice's score to 100.0")
    println("Total rows after upsert: ${db.count(TABLE)}")

    // 7. Delete Carol (primary key 3).
    db.deleteByPk(TABLE, 3L)
    println("Deleted Carol; remaining rows: ${db.count(TABLE)}")

    // 8. Cleanup.
    db.dropTable(TABLE)
    println("Dropped table $TABLE")
}
