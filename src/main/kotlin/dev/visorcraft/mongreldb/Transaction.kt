package dev.visorcraft.mongreldb

/**
 * Stages operations locally and commits them atomically in a single `/kit/txn`
 * request. The engine enforces unique, foreign-key, check, and trigger
 * constraints at commit time; on any violation all operations roll back and
 * [commit] throws a [ConflictException] carrying the server's structured error
 * code and offending op index.
 *
 * A [Transaction] is single-use: after [commit] or [rollback] it must not be
 * reused. Calling [commit] or [rollback] a second time throws
 * [IllegalStateException].
 *
 * Start one with [MongrelDB.beginTransaction] (or [MongrelDB.begin]):
 *
 * ```kotlin
 * val txn = db.beginTransaction()
 * txn.put("orders", mapOf(1L to 10L, 2L to "Dave"), returning = false)
 * txn.put("orders", mapOf(1L to 11L, 2L to "Eve"), returning = false)
 * txn.deleteByPk("orders", 2L)
 * val results = txn.commit() // atomic - all or nothing
 * ```
 *
 * @property committed `true` once [commit] or [rollback] has been called.
 */
public class Transaction internal constructor(private val client: MongrelDB) {
    private val ops: MutableList<Map<String, Any?>> = ArrayList()

    @Volatile
    public var committed: Boolean = false
        private set

    /**
     * Stages an insert. [returning], when `true`, asks the daemon to echo the
     * row in the per-operation result.
     *
     * @param table the target table
     * @param cells a column-id-to-value map
     * @param returning whether to echo the row in the result
     * @return this transaction, for chaining
     */
    public fun put(
        table: String,
        cells: Cells,
        returning: Boolean = false,
    ): Transaction {
        val op: MutableMap<String, Any?> = LinkedHashMap()
        val put: MutableMap<String, Any?> = LinkedHashMap()
        put["table"] = table
        put["cells"] = MongrelDB.flattenCells(cells)
        put["returning"] = returning
        op["put"] = put
        ops.add(op)
        return this
    }

    /**
     * Stages an insert-or-update. [updateCells], when non-null, supplies the
     * values written on a primary-key conflict; `null` means DO NOTHING.
     *
     * @param table the target table
     * @param cells the column-id-to-value map to insert
     * @param updateCells the values written on conflict, or `null`
     * @param returning whether to echo the row in the result
     * @return this transaction, for chaining
     */
    public fun upsert(
        table: String,
        cells: Cells,
        updateCells: Cells? = null,
        returning: Boolean = false,
    ): Transaction {
        val op: MutableMap<String, Any?> = LinkedHashMap()
        val upsert: MutableMap<String, Any?> = LinkedHashMap()
        upsert["table"] = table
        upsert["cells"] = MongrelDB.flattenCells(cells)
        upsert["returning"] = returning
        if (updateCells != null) {
            upsert["update_cells"] = MongrelDB.flattenCells(updateCells)
        }
        op["upsert"] = upsert
        ops.add(op)
        return this
    }

    /**
     * Stages a delete by the internal row id.
     *
     * @param table the target table
     * @param rowId the internal row id
     * @return this transaction, for chaining
     */
    public fun delete(table: String, rowId: Long): Transaction {
        val op: MutableMap<String, Any?> = LinkedHashMap()
        val del: MutableMap<String, Any?> = LinkedHashMap()
        del["table"] = table
        del["row_id"] = rowId
        op["delete"] = del
        ops.add(op)
        return this
    }

    /**
     * Stages a delete by primary-key value.
     *
     * @param table the target table
     * @param pk the primary-key value
     * @return this transaction, for chaining
     */
    public fun deleteByPk(table: String, pk: Any?): Transaction {
        val op: MutableMap<String, Any?> = LinkedHashMap()
        val del: MutableMap<String, Any?> = LinkedHashMap()
        del["table"] = table
        del["pk"] = pk
        op["delete_by_pk"] = del
        ops.add(op)
        return this
    }

    /** Alias mirroring the Java client's `deleteByPk`. */
    public fun deleteByPK(table: String, pk: Any?): Transaction = deleteByPk(table, pk)

    /** The number of staged operations. */
    public fun count(): Int = ops.size

    /**
     * Sends all staged operations atomically and returns the per-operation
     * results. [idempotencyKey], when non-blank, makes the commit safe to retry -
     * the daemon returns the original response on duplicate commits, even after
     * a crash.
     *
     * @param idempotencyKey an idempotency key, or `null`
     * @return the per-operation results, or an empty list if nothing was staged
     * @throws IllegalStateException if called twice on the same transaction
     * @throws ConflictException if a constraint violation rolled back the batch
     */
    public fun commit(idempotencyKey: String? = null): List<Row> {
        check(!committed) { ALREADY_COMMITTED }
        committed = true
        if (ops.isEmpty()) return emptyList()
        return client.commitTxn(ops, idempotencyKey) ?: emptyList()
    }

    /**
     * Discards all staged operations.
     *
     * @throws IllegalStateException if the transaction was already committed
     */
    public fun rollback() {
        check(!committed) { ALREADY_COMMITTED }
        ops.clear()
        committed = true
    }

    public companion object {
        /**
         * Message used when [commit] or [rollback] is called on a transaction
         * that has already been committed or rolled back.
         */
        public const val ALREADY_COMMITTED: String = "mongreldb: transaction already committed"
    }
}
