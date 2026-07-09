// Example: native query builder (range + primary-key lookups) in Kotlin.
//
// Run (from the repo root, with the client on the classpath):
//
//   kotlin -cp mongreldb-kotlin.jar examples/query_builder.kt
//
// Requires a mongreldb-server daemon running on http://127.0.0.1:8453.
//
// Creates a table, loads five rows with varying scores, then runs two
// native queries: a range scan over score in [60, 90], and an exact
// primary-key lookup for id == 4. Results are printed, then the table is
// dropped.

package com.example

import dev.visorcraft.mongreldb.MongrelDB

private const val DB_URL = "http://127.0.0.1:8453"
private const val TABLE = "example_query"

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

    // 3. Load five rows with varying scores.
    db.put(TABLE, row(1L, "Alice", 40.0))
    db.put(TABLE, row(2L, "Bob", 65.0))
    db.put(TABLE, row(3L, "Carol", 82.0))
    db.put(TABLE, row(4L, "Dave", 91.0))
    db.put(TABLE, row(5L, "Eve", 12.5))
    println("Inserted 5 rows")

    // 4. Range query: 60 <= score <= 90 (both inclusive). The "min"/"max"
    //    aliases map to the server's lo/hi keys.
    val rangeRows = db.query(TABLE)
        .where("range", mapOf("column" to 3L, "min" to 60.0, "max" to 90.0))
        .execute()
    printResult("range [60, 90] on score", rangeRows)

    // 5. Primary-key lookup: id == 4 (Dave).
    val pkRows = db.query(TABLE)
        .where("pk", mapOf("value" to 4L))
        .execute()
    printResult("pk == 4", pkRows)

    // 6. Cleanup.
    db.dropTable(TABLE)
    println("Dropped table $TABLE")
}
