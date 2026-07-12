// Example: atomic batch transactions with an idempotent retry in Kotlin.
//
// Run (from the repo root, with the client on the classpath):
//
//   kotlin -cp mongreldb-kotlin.jar examples/transactions.kt
//
// Requires a mongreldb-server daemon running on http://127.0.0.1:8453, or
// point MONGRELDB_URL at a running daemon.
//
// Creates a table, opens one transaction, stages three puts, and commits
// them atomically. It then verifies the row count. Finally it stages a
// fourth put and commits it twice with the SAME idempotency key: the
// daemon replays the first commit's result so the second commit is a
// no-op. The table is dropped at the end.

package com.example

import com.visorcraft.mongreldb.MongrelDB

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

fun main() {
    // Per-run unique table name and idempotency key (millis since epoch). A
    // reused idempotency key replays the original result and silently drops the
    // new batch, so the key must be unique per run too.
    val stamp = System.currentTimeMillis()
    val table = "example_txn_$stamp"
    val txnKey = "example-txn-key-$stamp"
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
        // 3. Stage three puts and commit them atomically.
        val txn1 = db.beginTransaction()
        txn1.put(table, row(1L, "Alice", 95.5))
        txn1.put(table, row(2L, "Bob", 82.0))
        txn1.put(table, row(3L, "Carol", 78.3))
        println("Staged ${txn1.count()} ops")
        txn1.commit()
        println("Committed transaction with 3 puts")

        // 4. Verify the row count.
        println("Total rows after commit: ${db.count(table)}")

        // 5. Idempotent retry: stage a fourth put and commit twice with the
        //    same idempotency key. The second commit is replayed as a no-op
        //    (a fresh Transaction must be used, but the same key dedupes it).
        val txn2a = db.beginTransaction()
        txn2a.put(table, row(4L, "Dave", 60.0))
        txn2a.commit(txnKey)
        println("Committed 4th put with idempotency key $txnKey")

        val txn2b = db.beginTransaction()
        txn2b.put(table, row(4L, "Dave", 60.0))
        txn2b.commit(txnKey)
        println("Recommitted with same key (idempotent replay)")

        println("Total rows after idempotent retry: ${db.count(table)}")
    } finally {
        try {
            db.dropTable(table)
            println("Dropped table $table")
        } catch (e: Exception) {
            System.err.println("cleanup dropTable failed: ${e.message}")
        }
    }
}
