// Example: native query builder (range + primary-key lookups) in Kotlin.
//
// Run (from the repo root, with the client on the classpath):
//
//   kotlin -cp mongreldb-kotlin.jar examples/query_builder.kt
//
// Requires a mongreldb-server daemon running on http://127.0.0.1:8453, or
// point MONGRELDB_URL at a running daemon.
//
// Creates a table, loads five rows with varying scores, then runs two
// native queries: a range scan over score in [60, 90], and an exact
// primary-key lookup for id == 4. Results are printed, then the table is
// dropped.

package com.example

import com.visorcraft.mongreldb.MongrelDB
import com.visorcraft.mongreldb.Row

private const val DB_URL_DEFAULT = "http://127.0.0.1:8453"

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

// A decoded Row is keyed by the column id as a JSON string (e.g. "2"),
// matching the client's `Row = Map<String, Any?>` type.
private fun printResult(label: String, rows: List<Row>) {
    println("  $label: ${rows.size} rows")
    for (r in rows) {
        println("    { ${r.entries.joinToString(", ") { (k, v) -> "col$k=$v" }} }")
    }
}

fun main() {
    // Per-run unique table name (millis since epoch).
    val table = "example_query_${System.currentTimeMillis()}"
    val dbUrl = System.getenv("MONGRELDB_URL")?.takeIf { it.isNotBlank() } ?: DB_URL_DEFAULT

    val db = MongrelDB(dbUrl)

    // 1. Health check; bail out if the daemon is unreachable.
    if (!db.health()) {
        System.err.println("daemon not reachable at $dbUrl")
        return
    }
    println("Connected to MongrelDB")

    // 2. Create the table.
    val tableId = db.createTable(
        table,
        listOf(
            column(1L, "id", "int64", primaryKey = true),
            column(2L, "name", "varchar", primaryKey = false),
            column(3L, "score", "float64", primaryKey = false),
        ),
    )
    println("Created table $table (id $tableId)")

    try {
        // 3. Load five rows with varying scores.
        db.put(table, row(1L, "Alice", 40.0))
        db.put(table, row(2L, "Bob", 65.0))
        db.put(table, row(3L, "Carol", 82.0))
        db.put(table, row(4L, "Dave", 91.0))
        db.put(table, row(5L, "Eve", 12.5))
        println("Inserted 5 rows")

        // 4. Range query: 60 <= score <= 90 (both inclusive). The "min"/"max"
        //    aliases map to the server's lo/hi keys.
        val rangeRows = db.query(table)
            .where("range_f64", mapOf("column" to 3L, "min" to 60.0, "max" to 90.0, "min_inclusive" to true, "max_inclusive" to true))
            .execute()
        printResult("range [60, 90] on score", rangeRows)

        // 5. Primary-key lookup: id == 4 (Dave).
        val pkRows = db.query(table)
            .where("pk", mapOf("value" to 4L))
            .execute()
        printResult("pk == 4", pkRows)
    } finally {
        try {
            db.dropTable(table)
            println("Dropped table $table")
        } catch (e: Exception) {
            System.err.println("cleanup dropTable failed: ${e.message}")
        }
    }
}
