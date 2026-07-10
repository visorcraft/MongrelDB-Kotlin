# Quickstart

Zero to a running MongrelDB Kotlin program in fifteen minutes. This guide walks
through installing the prerequisites, starting the daemon, and writing, running,
and understanding a complete program.

---

## 1. Prerequisites

You need two things installed: a JDK and a `mongreldb-server` daemon.

### Install JDK 11 or newer

The client targets JVM 11 bytecode and has no external dependencies, so any JDK
11+ works. Verify it:

```sh
java -version
# openjdk version "11.0.x" (or newer)
```

If you do not have it, install from <https://adoptium.net/> or your package
manager (for example `pacman -S jdk-openjdk`, `brew install openjdk@21`).

### Install mongreldb-server

Fetch a prebuilt server binary from the
[MongrelDB releases](https://github.com/visorcraft/MongrelDB/releases):

```sh
mkdir -p bin
curl -fsSL -o bin/mongreldb-server \
  https://github.com/visorcraft/MongrelDB/releases/download/v0.46.2/mongreldb-server-linux-x64
chmod +x bin/mongreldb-server
```

Verify it runs:

```sh
./bin/mongreldb-server --version
```

## 2. Start the daemon

By default `mongreldb-server` listens on `http://127.0.0.1:8453` and stores data
in the current working directory.

```sh
mkdir -p /tmp/mdb-data && cd /tmp/mdb-data
/path/to/mongreldb-server
```

In another terminal, sanity-check it:

```sh
curl http://127.0.0.1:8453/health
# ok
```

Leave the daemon running for the rest of this guide.

## 3. Create a project and pull in the client

### With Gradle (Kotlin DSL)

Add the dependency to `build.gradle.kts`:

```kotlin
dependencies {
    implementation("dev.visorcraft:mongreldb-kotlin:0.1.0")
}
```

Make sure your Kotlin compiler targets JVM 11 or newer:

```kotlin
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}
```

### With Gradle (Groovy DSL)

```groovy
implementation 'dev.visorcraft:mongreldb-kotlin:0.1.0'
```

### With Maven

```xml
<dependency>
  <groupId>dev.visorcraft</groupId>
  <artifactId>mongreldb-kotlin</artifactId>
  <version>0.1.0</version>
</dependency>
```

The artifact pulls in the Kotlin standard library transitively and nothing else.

## 4. Write your first program

Create `src/main/kotlin/com/example/Main.kt`:

```kotlin
package com.example

import dev.visorcraft.mongreldb.MongrelDB

fun main() {
    // 1. Connect to the daemon. A null/blank url defaults to
    //    http://127.0.0.1:8453.
    val db = MongrelDB("http://127.0.0.1:8453")

    // 2. Health check before doing anything else.
    if (!db.health()) {
        System.err.println("daemon not reachable")
        return
    }

    // 3. Create a table. Each column is a Map with id, name, ty, and flags.
    //    The first column is the primary key. Column ids are stable on-wire
    //    identifiers - use them everywhere else.
    val tableId = db.createTable(
        "orders",
        listOf(
            column(1L, "id", "int64", primaryKey = true),
            column(2L, "customer", "varchar", primaryKey = false),
            column(3L, "amount", "float64", primaryKey = false),
        ),
    )
    println("created table id: $tableId")

    // 4. Insert rows. cells maps column id (Long) -> value. A null
    //    idempotency key is fine for a one-shot demo.
    db.put("orders", mapOf(1L to 1L, 2L to "Alice", 3L to 99.5))
    db.put("orders", mapOf(1L to 2L, 2L to "Bob", 3L to 150.0))

    // 5. Query with a native index condition. The range index serves this in
    //    sub-millisecond. Projection selects only column ids 1 and 2.
    val rows = db.query("orders")
        .where("range", mapOf("column" to 3L, "min" to 100.0))
        .projection(listOf(1L, 2L))
        .limit(100)
        .execute()
    for (row in rows) {
        println("row: $row")
    }

    // 6. Count the rows.
    println("total rows: ${db.count("orders")}")
}

/** Builds a column descriptor Map for createTable. */
private fun column(id: Long, name: String, ty: String, primaryKey: Boolean): Map<String, Any?> =
    mapOf(
        "id" to id,
        "name" to name,
        "ty" to ty,
        "primary_key" to primaryKey,
        "nullable" to false,
    )
```

Run it (Gradle):

```sh
./gradlew run -q --main-class=com.example.Main
```

You should see:

```
created table id: 1
row: {2=Bob}
total rows: 2
```

## 5. What each part does

| Code | What it does |
|------|--------------|
| `MongrelDB(url)` | Builds a client targeting one daemon. Thread-safe once constructed. |
| `db.health()` | GET `/health`; returns `true` when the daemon answers. Always check before real work. |
| `db.createTable(name, columns)` | POST `/kit/create_table`. Column `id`s are the on-wire identifiers; use them everywhere else. |
| `db.put(table, cells)` | Single-op transaction: POST `/kit/txn` with one `put` op. `cells` is flattened to `[col_id, val, ...]`. |
| `db.query(table).where(...)` | Builds a `/kit/query` body. `where` pushes a condition down to a native index. |
| `.projection(listOf(1L, 2L))` | Server returns only those column ids, saving bandwidth. |
| `.limit(100)` | Caps the result; check `builder.truncated` afterward to detect overflow. |
| `.execute()` | Sends the query and decodes the `rows` list. |
| `db.count(table)` | GET `/tables/{name}/count`. |

## 6. Common pitfalls

**Using the column name instead of the column id.** Every on-wire API uses the
numeric `id` from `createTable`, never the `name`. The query builder's `column`
alias maps to the server's `column_id` - pass the `Long` id, not the string
name:

```kotlin
// Wrong:
.where("range", mapOf("column" to "amount", "min" to 100.0))
// Right:
.where("range", mapOf("column" to 3L, "min" to 100.0))
```

**Using an `Int` where a `Long` column id is expected.** Column ids are `Long`.
Writing `mapOf(1 to "Alice")` boxes the key to `Int`, which the flatten step
will not accept as a column id. Always use the `L` suffix: `1L`, `2L`, ...

**Treating a single `put` as non-transactional.** `put` is a one-op
transaction. A unique constraint violation throws `ConflictException` (HTTP
409), not a silent no-op.

**Calling `commit` twice on the same `Transaction`.** The second call throws
`IllegalStateException`. Create a fresh `db.beginTransaction()` for each logical
unit of work.

**Reusing a `QueryBuilder` and expecting a fresh `truncated`.** `truncated`
reflects the most recent `execute`. Build a new query, or re-run `execute`
before reading it.

**Expecting `sql` to always return rows.** The `/sql` endpoint streams Arrow IPC
for `SELECT` in most builds, so `sql` returns an empty list (not an exception)
for result sets. Use it for DDL/DML and statements whose success is the signal;
use the native query builder for typed row retrieval.

**Pointing at a daemon that requires auth.** If the daemon was started with
`--auth-token` or `--auth-users`, every call throws `AuthException` unless you
construct the client with a token or Basic credentials. See [auth.md](auth.md).

## Next steps

- [transactions.md](transactions.md) - atomic batches, idempotency, retries
- [queries.md](queries.md) - every native index condition
- [sql.md](sql.md) - recursive CTEs, window functions, `CREATE TABLE AS SELECT`
- [auth.md](auth.md) - bearer tokens, basic auth, user/role management
- [errors.md](errors.md) - the full exception hierarchy and recovery patterns
