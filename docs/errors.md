# Error handling

Every non-2xx response from the daemon is mapped to a typed Kotlin exception.
This is the complete reference: the exception hierarchy, the properties carried
on each exception, the HTTP-status mapping, the daemon's error envelope, and
recovery patterns for each category.

---

## The exception hierarchy

All client errors extend `MongrelDBException` (a `RuntimeException`, so
unchecked). Catch `MongrelDBException` to handle any failure, or catch one of
the specific subclasses:

```
RuntimeException
+-- MongrelDBException            (base; carries status, code, opIndex)
    +-- AuthException             HTTP 401 / 403
    +-- NotFoundException         HTTP 404
    +-- ConflictException         HTTP 409
    +-- QueryException            HTTP 400 / 5xx / everything else
```

| Exception | Meaning | Typical cause |
|-----------|---------|---------------|
| `MongrelDBException` | Base class; any client-side failure | Catch-all parent |
| `AuthException` | HTTP 401 or 403 | Missing/bad credentials against an auth-enabled daemon |
| `NotFoundException` | HTTP 404 | Missing table, schema, or other resource |
| `ConflictException` | HTTP 409 | Unique, foreign-key, check, or trigger violation at commit |
| `QueryException` | HTTP 400 or 5xx | Malformed request, transport failure, server error, JSON decode errors |

`QueryException` is also the type raised for client-side failures that do not
correspond to an HTTP response (for example an `IOException` from the transport,
or a malformed JSON body). In those cases [status][MongrelDBException.status]
returns `-1`.

## Properties carried on every exception

`MongrelDBException` exposes three properties inherited by all subclasses:

| Property | Returns |
|----------|---------|
| `status` | The HTTP status code, or `-1` when unknown (client-side failure). |
| `code` | The server's structured error code, for example `"UNIQUE_VIOLATION"`, or `null`. |
| `opIndex` | The offending op index within a batch, or `null` when not reported. |

Plus the inherited `message`, `cause`, and the usual `RuntimeException`
behavior.

The daemon's JSON error envelope (decoded into the properties above):

```json
{
  "status": "aborted",
  "error": {
    "code": "UNIQUE_VIOLATION",
    "message": "duplicate key in column 1",
    "op_index": 0
  }
}
```

Structured codes you will commonly see in `code`:

| `code` | Meaning |
|--------|---------|
| `UNIQUE_VIOLATION` | A unique/PK constraint rejected the commit |
| `FK_VIOLATION` | A foreign-key reference was missing |
| `CHECK_VIOLATION` | A check constraint or trigger rejected the commit |
| `NOT_FOUND` | A named resource (table, schema) does not exist |

## HTTP status -> exception mapping

| HTTP status | Exception | Notes |
|-------------|-----------|-------|
| 401, 403 | `AuthException` | Bad/missing credentials |
| 404 | `NotFoundException` | Resource not found |
| 409 | `ConflictException` | Constraint violation at commit |
| 400 | `QueryException` | Malformed request / bad query |
| 5xx | `QueryException` | Daemon-side failure |
| other non-2xx | `QueryException` | Catch-all |
| 2xx | (no exception) | Success |
| transport failure | `QueryException` | `status == -1` |

## Discriminating errors

### By type - catch the specific subclass

```kotlin
try {
    db.schemaFor("missing_table")
} catch (e: NotFoundException) {
    println("table does not exist")
} catch (e: ConflictException) {
    println("unexpected conflict on a read")
} catch (e: AuthException) {
    println("bad credentials")
} catch (e: QueryException) {
    println("server error or malformed request: ${e.message}")
}
```

Because all four subclasses share the parent, a single
`catch (e: MongrelDBException)` handles everything if you only need to know it
failed.

### By details - read the properties

```kotlin
try {
    db.schemaFor("missing_table")
} catch (e: MongrelDBException) {
    println("status=${e.status} code=${e.code} op=${e.opIndex} msg=${e.message}")
}
```

Combine the two for constraint-aware handling:

```kotlin
try {
    txn.commit()
} catch (e: ConflictException) {
    println("constraint ${e.code} at op ${e.opIndex}: ${e.message}")
}
```

## Recovery patterns

### Auth failure - do not retry blindly

A retry will not fix bad credentials. Surface the error to the caller or
operator.

```kotlin
try {
    db.put("orders", cells)
} catch (e: AuthException) {
    // Refresh credentials from your secret store, or fail fast.
    throw IllegalStateException("credentials rejected; refresh token", e)
}
```

### Not found - fall back, do not crash

For lookups by primary key, a 404 may be a normal "absent" result.

```kotlin
try {
    val rows = db.query("orders")
        .where("pk", mapOf("value" to id))
        .execute()
    return rows
} catch (e: NotFoundException) {
    return emptyList() // table missing - treat as empty
}
```

Note: a `pk` query against an existing table returns zero rows, not a 404;
`NotFoundException` here means the table itself is missing.

### Constraint conflict - report the offending op

```kotlin
try {
    txn.commit()
} catch (e: ConflictException) {
    throw RuntimeException(
        "op ${e.opIndex} violated ${e.code}: ${e.message}",
        e,
    )
}
```

The engine already rolled back the whole batch - there is nothing to undo.

### Transient failure - retry with an idempotency key

`QueryException` covers transport and 5xx failures. With an idempotency key,
retrying a transaction is safe (see [transactions.md](transactions.md)).

```kotlin
fun run(txn: Transaction, key: String) {
    try {
        txn.commit(key)
    } catch (e: AuthException) {
        throw e // not transient
    } catch (e: ConflictException) {
        throw e // not transient
    } catch (e: MongrelDBException) {
        // QueryException / network - caller may retry with the same key.
        throw e
    }
}
```

### Transaction-state error

`Transaction.commit` and `Transaction.rollback` throw `IllegalStateException`
("mongreldb: transaction already committed") if called twice. Fix the control
flow rather than catching it.

```kotlin
txn.commit()
txn.commit() // throws IllegalStateException - logic bug
```

## Quick reference

```kotlin
import dev.visorcraft.mongreldb.*

// Type-based discrimination:
try {
    db.put("orders", cells)
} catch (e: AuthException) {
    // 401/403
} catch (e: NotFoundException) {
    // 404
} catch (e: ConflictException) {
    // 409; e.code, e.opIndex
} catch (e: QueryException) {
    // 400/5xx/transport; e.status == -1 for client-side failures
} catch (e: MongrelDBException) {
    // catch-all parent
}

// Property access on any MongrelDBException:
//   e.status    -> Int (HTTP status, or -1)
//   e.code      -> String? ("UNIQUE_VIOLATION", ...)
//   e.opIndex   -> Int? (offending op)
//   e.message   -> human-readable message
```

## Next steps

- [transactions.md](transactions.md) - constraint handling and retries in context
- [auth.md](auth.md) - credential management
