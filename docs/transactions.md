# Transactions

MongrelDB commits every write through a single atomic transaction endpoint
(`POST /kit/txn`). This guide covers the two ways to use it - a one-shot single
op, and a staged batch - plus idempotency keys for safe retries, typed
constraint-violation handling, and rollback.

The engine enforces `UNIQUE`, foreign-key, check, and trigger constraints at
**commit time**. A violation aborts the entire batch: no op in the batch becomes
visible, and `commit` throws `ConflictException`.

---

## Single puts vs. batch transactions

### Single op: `MongrelDB.put`

`MongrelDB.put` is a convenience wrapper that sends a one-op transaction. Use it
when a write is independent and you do not need atomicity across multiple rows.

```kotlin
// One row, one atomic op. A null idempotency key disables deduplication.
val res = db.put("orders", mapOf(1L to 1L, 2L to "Alice", 3L to 99.5))
```

`MongrelDB.upsert`, `MongrelDB.delete`, and `MongrelDB.deleteByPk` are the same
shape: single-op transactions.

### Batch: `MongrelDB.beginTransaction` + `Transaction`

When several writes must succeed or fail together, stage them on a
`Transaction` and commit once. All ops go to the server in a single HTTP request
and commit atomically.

```kotlin
val txn = db.beginTransaction()
txn.put("orders", mapOf(1L to 10L, 2L to "Dave", 3L to 50.0))
txn.put("orders", mapOf(1L to 11L, 2L to "Eve", 3L to 75.0))
txn.deleteByPk("orders", 2L)

val results = txn.commit() // atomic - all or nothing
println("committed ${results.size} ops")
```

The `returning` argument to `Transaction.put` asks the daemon to echo the
written row back in the result map - useful for reading server-assigned values.

```kotlin
val txn = db.beginTransaction()
txn.put("orders", mapOf(1L to 42L, 2L to "Hal", 3L to 12.0), returning = true)
val res = txn.commit()
println("server echoed: ${res[0]}")
```

`Transaction.upsert(table, cells, updateCells, returning)` takes an `updateCells`
map applied on a primary-key conflict. A `null` `updateCells` means "do nothing
on conflict".

```kotlin
txn.upsert(
    "orders",
    mapOf(1L to 1L, 2L to "Alice", 3L to 120.0),     // insert these...
    updateCells = mapOf(3L to 120.0),                // ...or update only amount on conflict
)
```

## Idempotency keys for safe retries

Networks drop requests and daemons crash after committing but before replying.
An idempotency key makes a commit safe to retry: the daemon remembers the key
and replays the **original** result on a duplicate commit, even across restarts.

Pass the key as the argument to `commit` (or the `idempotencyKey` argument to
`MongrelDB.put` / `MongrelDB.upsert`):

```kotlin
// A handler that must not double-charge, even if the client retries or the
// connection drops after the daemon committed.
fun charge(orderId: String) {
    val txn = db.beginTransaction()
    txn.put("charges", mapOf(1L to orderId, 2L to 199.0))

    // Use a stable, business-meaningful key derived from the request. On a
    // retry with the same key the daemon returns the first commit's result
    // instead of inserting a second row.
    txn.commit("charge:$orderId")
}
```

Rules for keys:

- Any non-blank string works. Prefer content-derived, globally-unique values
  (for example `"charge:$orderId"`).
- `null` (or a blank string) disables idempotency - a retry will commit again.
- The key scopes the **entire batch**, not individual ops. Reuse the exact same
  ops and key together when retrying.

A safe retry loop - build the transaction inside the loop so a failed attempt
can be retried cleanly:

```kotlin
fun commitWithRetry(key: String, stage: (Transaction) -> Unit) {
    var attempt = 0
    while (true) {
        val txn = db.beginTransaction()
        stage(txn)
        try {
            txn.commit(key)
            return
        } catch (e: ConflictException) {
            throw e // not transient - do not retry
        } catch (e: AuthException) {
            throw e // not transient - do not retry
        } catch (e: MongrelDBException) {
            // Network/server error (QueryException). The idempotency key makes
            // it safe to retry.
            if (attempt == 2) throw e
            Thread.sleep(1000L shl attempt) // 1s, 2s, 4s
            attempt++
        }
    }
}
```

## Handling constraint violations

Constraint violations arrive as HTTP 409, mapped to `ConflictException`. It
extends `MongrelDBException` and carries the structured [code][ConflictException.code]
and [opIndex][ConflictException.opIndex]:

```kotlin
val txn = db.beginTransaction()
txn.put("orders", mapOf(1L to 1L)) // duplicate PK

try {
    txn.commit()
} catch (e: ConflictException) {
    when (e.code ?: "") {
        "UNIQUE_VIOLATION" ->
            println("duplicate at op ${e.opIndex}: ${e.message}")
        "FK_VIOLATION" ->
            println("missing parent at op ${e.opIndex}: ${e.message}")
        "CHECK_VIOLATION" ->
            println("check failed at op ${e.opIndex}: ${e.message}")
        else -> println("other conflict: ${e.message}")
    }
}
```

The error envelope from the daemon looks like:

```json
{"status": "aborted", "error": {"code": "UNIQUE_VIOLATION", "message": "...", "op_index": 0}}
```

`opIndex` points at the offending op within the batch so you can report which
row caused the failure. It returns `null` when the server did not report one.

For simple category checks, catch the specific subclass:

```kotlin
try {
    txn.commit()
} catch (e: ConflictException) {
    // any constraint violation
} catch (e: NotFoundException) {
    // table or row missing
} catch (e: AuthException) {
    // bad credentials
}
```

## Rollback after failure

There are two notions of "rollback":

1. **Server-side.** When `commit` throws `ConflictException`, the engine has
   already discarded the entire batch. Nothing was written; there is no server
   rollback to perform.
2. **Client-side.** `Transaction.rollback()` clears the locally staged ops.
   Call it to release the `Transaction` when you decide not to commit (for
   example, after a validation error in your own code, before ever sending).

```kotlin
val txn = db.beginTransaction()
txn.put("orders", mapOf(1L to 1L, 2L to "Iris", 3L to 5.0))

if (!businessRuleOk()) {
    // Throw the staged ops away locally. Nothing has been sent to the daemon.
    txn.rollback()
    return
}

try {
    txn.commit()
} catch (e: ConflictException) {
    // On conflict the server already rolled back. No client-side cleanup of
    // server data is needed.
    System.err.println("conflict: ${e.message}")
}
```

`rollback` and `commit` both throw `IllegalStateException` if the transaction
was already committed or rolled back. Treat that as a programming error to fix
upstream, not a runtime condition to silence.

### Recovering from a failed batch

Because a failed commit rejects the whole batch, the usual recovery is to
re-issue the ops that are still valid, optionally splitting out the offender.
Keep your own list of the logical ops if you need surgical retry, since
`Transaction` does not expose its staged ops.

## Summary

| Goal | Use |
|------|-----|
| One independent write | `MongrelDB.put` / `upsert` / `delete` / `deleteByPk` |
| Several writes that must commit together | `MongrelDB.beginTransaction` + `Transaction.commit` |
| Retry safely after a network blip | `commit(idempotencyKey)` with a stable key |
| Distinguish constraint classes | catch `ConflictException`, read `.code` / `.opIndex` |
| Abort before sending | `Transaction.rollback()` |

See [errors.md](errors.md) for the full exception hierarchy and
[queries.md](queries.md) for read patterns.
