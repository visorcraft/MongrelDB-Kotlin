<p align="center">
  <img src="assets/mongrel.png" alt="MongrelDB logo" width="250" />
</p>

<h1 align="center">MongrelDB Kotlin Client</h1>

<p align="center">
  <b>Pure Kotlin client for MongrelDB - embedded+server database with SQL, vector search, full-text search, and AI-native retrieval.</b>
  <br />
  No external dependencies - built on the JDK's <code>java.net.HttpURLConnection</code>. The API mirrors the MongrelDB Java, PHP, and Go clients.
</p>

<p align="center">
  <a href="https://github.com/visorcraft/MongrelDB-Kotlin/actions/workflows/ci.yml"><img src="https://github.com/visorcraft/MongrelDB-Kotlin/actions/workflows/ci.yml/badge.svg" alt="Kotlin CI" /></a>
  <a href="https://kotlinlang.org/"><img src="https://img.shields.io/badge/Kotlin-JVM%201.1%2B-7F52FF.svg" alt="Kotlin" /></a>
  <a href="https://central.sonatype.com/artifact/com.visorcraft/mongreldb-kotlin"><img src="https://img.shields.io/maven-central/v/com.visorcraft/mongreldb-kotlin.svg?label=Maven%20Central" alt="Maven Central" /></a>
  <a href="#license"><img src="https://img.shields.io/badge/license-MIT%20OR%20Apache--2.0-blue.svg" alt="License" /></a>
</p>

## Package

| Surface | Coordinates | Install |
|---|---|---|
| Kotlin client | `com.visorcraft:mongreldb-kotlin:0.60.2` | Gradle / Maven snippets below |

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("com.visorcraft:mongreldb-kotlin:0.60.2")
}
```

### Gradle (Groovy DSL)

```groovy
implementation 'com.visorcraft:mongreldb-kotlin:0.60.2'
```

### Maven

```xml
<dependency>
  <groupId>com.visorcraft</groupId>
  <artifactId>mongreldb-kotlin</artifactId>
  <version>0.60.2</version>
</dependency>
```

The artifact pulls in the Kotlin standard library transitively and nothing else.
The bytecode target is JVM 11, so it runs on Java 11 or newer.

## Requirements

- **JVM 11 or newer** (Java 11, 17, 21 all supported)
- A running [`mongreldb-server`](https://github.com/visorcraft/MongrelDB) daemon

## What It Provides

- **Typed CRUD** over the Kit transaction endpoint: `put`, `upsert` (insert-or-update on PK conflict), `delete` by row id or primary key, all with optional idempotency keys for safe retries.
- **Fluent query builder** that pushes conditions down to the engine's specialized indexes for sub-millisecond lookups: bitmap equality/IN, learned-range, null checks, FM-index full-text search, HNSW vector similarity (`ann`), and sparse vector match. Friendly aliases (`column` -> `column_id`, `min`/`max` -> `lo`/`hi`) are translated to the server's on-wire keys.
- **Idempotent batch transactions** - operations staged locally and committed atomically, with the engine enforcing unique, foreign-key, and check constraints at commit time. Idempotency keys return the original response on duplicate commits, even after a crash.
- **Full SQL access** through the DataFusion-backed `/sql` endpoint: recursive CTEs, window functions, `CREATE TABLE AS SELECT`, materialized views, and multi-statement execution.
- **Schema management**: typed table creation, full schema catalog, and per-table descriptors.
- **User/role/credentials management** via SQL: Argon2id-hashed catalog users, roles, and `GRANT`/`REVOKE` table-level permissions, all executed through `sql`.
- **Maintenance**: compaction (all tables or per-table).
- **Pluggable transport**: built on the JDK's `java.net.HttpURLConnection`, with configurable connect and read timeouts. Bearer token and HTTP Basic auth are first-class options.
- **Typed errors**: `AuthException` (401/403), `NotFoundException` (404), `ConflictException` (409, with error code + op index), and `QueryException` (everything else), all extending `MongrelDBException` and carrying the status code and decoded server envelope.

## Examples

Task-focused, commented guides live in [`docs/`](docs):

- [Quickstart](docs/quickstart.md) - install, start the daemon, write and run a complete program.
- [Transactions](docs/transactions.md) - batch commits, idempotency keys, constraint handling.
- [Queries](docs/queries.md) - every native condition type and the index it pushes down to.
- [SQL](docs/sql.md) - recursive CTEs, window functions, advanced SQL.
- [Authentication](docs/auth.md) - Bearer token, HTTP Basic, and open modes.
- [Errors](docs/errors.md) - the exception hierarchy and recovery patterns.

## Quick Example

```kotlin
import com.visorcraft.mongreldb.MongrelDB

fun main() {
    // Connect to a running mongreldb-server daemon.
    val db = MongrelDB("http://127.0.0.1:8453")

    // Create a table. Column ids are stable on-wire identifiers.
    db.createTable(
        "orders",
        listOf(
            mapOf("id" to 1, "name" to "id", "ty" to "int64", "primary_key" to true, "nullable" to false),
            mapOf("id" to 2, "name" to "customer", "ty" to "varchar", "primary_key" to false, "nullable" to false),
            mapOf("id" to 3, "name" to "amount", "ty" to "float64", "primary_key" to false, "nullable" to false),
            // Optional column keys are forwarded verbatim: `enum_variants`
            // constrains values; scalar `default_value` (string, integer,
            // boolean, explicit null, or literal "now"/"uuid") fills omitted
            // cells, while dynamic `default_expr` ("now" or "uuid") is sent
            // instead when the default must be evaluated server-side.
            mapOf(
                "id" to 4, "name" to "status", "ty" to "int32",
                "primary_key" to false, "nullable" to false,
                "enum_variants" to listOf("open", "shipped", "closed"),
                "default_value" to "open",
            ),
        ),
    )

    // Insert rows (cells map column id -> value).
    db.put("orders", mapOf(1L to 1L, 2L to "Alice", 3L to 99.50))
    db.put("orders", mapOf(1L to 2L, 2L to "Bob", 3L to 150.00))

    // Upsert (insert or update on PK conflict).
    db.upsert(
        "orders",
        cells = mapOf(1L to 1L, 2L to "Alice", 3L to 120.00),
        updateCells = mapOf(3L to 120.00),
    )

    // Query with a native index condition (learned-range index).
    val rows = db.query("orders")
        .where("range", mapOf("column" to 3L, "min" to 100.0))
        .projection(listOf(1L, 2L))
        .limit(100)
        .execute()
    println("rows: ${rows.size}")

    val n = db.count("orders")
    println("count: $n") // 2

    // Run SQL.
    db.sql("UPDATE orders SET amount = 200.0 WHERE customer = 'Bob'")
}
```

## Authentication

```kotlin
// Bearer token (--auth-token mode)
val db = MongrelDB("http://127.0.0.1:8453", token = "my-secret-token")

// HTTP Basic (--auth-users mode)
val db = MongrelDB("http://127.0.0.1:8453", username = "admin", password = "s3cret")

// Default URL (http://127.0.0.1:8453) when url is null/blank
val db = MongrelDB()

// Configurable timeouts (defaults are 30s each)
val db = MongrelDB(
    url = "http://127.0.0.1:8453",
    token = "secret",
    connectTimeoutMillis = 10_000,
    readTimeoutMillis = 60_000,
)
```

A bearer token takes precedence over basic-auth credentials when both are
supplied.

## Batch transactions

Operations are staged locally and committed atomically. The engine enforces
unique, foreign-key, and check constraints at commit time.

```kotlin
var txn = db.beginTransaction()
txn.put("orders", mapOf(1L to 10L, 2L to "Dave", 3L to 50.00))
txn.put("orders", mapOf(1L to 11L, 2L to "Eve", 3L to 75.00))
txn.deleteByPk("orders", 2L)

try {
    val results = txn.commit() // atomic - all or nothing
} catch (e: ConflictException) {
    // A constraint violation rolled back every op.
    println("duplicate: ${e.code} at op ${e.opIndex}")
    txn.rollback() // discard locally as well
}

// Idempotent commit - safe to retry; the daemon returns the original response.
txn = db.beginTransaction()
txn.put("orders", mapOf(1L to 20L, 2L to "Frank", 3L to 100.00))
txn.commit(idempotencyKey = "order-20-create")
```

A `Transaction` is single-use: calling `commit` or `rollback` twice throws
`IllegalStateException`. Create a fresh one with `db.beginTransaction()` for
each batch.

## Native query builder

Conditions push down to the engine's specialized indexes. The builder accepts
friendly aliases that are translated to the server's on-wire keys: `column`
(-> `column_id`), `min`/`max` (-> `lo`/`hi`). The canonical keys are also
accepted directly.

```kotlin
// Bitmap equality (low-cardinality columns).
db.query("orders")
    .where("bitmap_eq", mapOf("column" to 2L, "value" to "Alice"))
    .execute()

// Range query (learned-range index).
db.query("orders")
    .where("range", mapOf("column" to 3L, "min" to 50.0, "max" to 150.0))
    .limit(100).execute()

// Full-text search (FM-index).
db.query("documents")
    .where("fm_contains", mapOf("column" to 2L, "pattern" to "database performance"))
    .limit(10).execute()

// Vector similarity search (HNSW).
db.query("embeddings")
    .where("ann", mapOf("column" to 2L, "query" to doubleArrayOf(0.1, 0.2, 0.3), "k" to 10))
    .execute()

// Check whether a result was capped by the limit.
val q = db.query("orders")
    .where("range", mapOf("column" to 3L, "min" to 0L))
    .limit(100)
val rows = q.execute()
if (q.truncated) {
    // result set hit the limit; more matches exist on the server
}
```

## SQL

```kotlin
db.sql("INSERT INTO orders (id, customer, amount) VALUES (99, 'Zoe', 999.0)")
db.sql("CREATE TABLE archive AS SELECT * FROM orders WHERE amount > 500")

// Recursive CTEs and window functions
db.sql("WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM r WHERE n<10) SELECT n FROM r")
db.sql("SELECT id, ROW_NUMBER() OVER (PARTITION BY customer ORDER BY amount DESC) FROM orders")
```

The `/sql` endpoint now returns JSON by default for `SELECT`s; `sql()` sends
`format: "json"` and returns the parsed rows (a `List<Map<String, Any?>>`
keyed by column name). It returns an empty list for statements that produce no
rows (DDL/DML).

## History retention and time travel

The client can set and read the database-wide history retention window. Writes
advance the epoch, and `AS OF EPOCH` queries read older versions of a row as
long as the epoch is within the retention window.

```kotlin
// Keep at least 100 epochs of history.
db.setHistoryRetentionEpochs(100L)
println(db.historyRetentionEpochs())   // 100
println(db.earliestRetainedEpoch())    // earliest epoch still readable

// Insert, then force a commit to pin the epoch.
db.put("orders", mapOf(1L to 1L, 2L to "orig"))
// (the server exposes POST /tables/{name}/commit to flush and return the epoch)

// Later, update the row.
db.sql("UPDATE orders SET customer = 'updated' WHERE id = 1")

// Read the older version with SQL AS OF EPOCH.
val old = db.sql("SELECT customer FROM orders AS OF EPOCH 5 WHERE id = 1")
```

Retention is a durable, database-wide policy that requires `ADMIN` permission
when the daemon runs with auth. Increasing retention cannot restore history
already pruned by a smaller earlier window.

## User & role management

User, role, and permission management is performed through SQL against the
daemon's catalog. Passwords are Argon2id-hashed server-side.

```kotlin
db.sql("CREATE USER admin WITH PASSWORD 's3cret-pw'")
db.sql("ALTER USER admin SET ADMIN TRUE")

db.sql("CREATE ROLE analyst")
db.sql("GRANT select ON orders TO analyst") // table-level permission
db.sql("GRANT analyst TO alice")

db.sql("SELECT username FROM catalog.users") // list users
db.sql("SELECT name FROM catalog.roles")     // list roles
```

## Error handling

Every non-2xx response is mapped to a typed exception. Catch the specific
subclass for the category, or catch `MongrelDBException` to handle any failure.
Each carries the HTTP status code and the server's decoded error envelope
(`code`, `opIndex`).

```kotlin
try {
    db.schemaFor("missing_table")
} catch (e: NotFoundException) {
    println("not found: ${e.message}")
} catch (e: AuthException) {
    println("not authorized: ${e.message}")
} catch (e: ConflictException) {
    println("constraint ${e.code} at op ${e.opIndex}")
} catch (e: QueryException) {
    println("query/server error: ${e.message} (status ${e.status})")
}

// Or inspect directly on the base type:
try {
    db.schemaFor("missing_table")
} catch (e: MongrelDBException) {
    println("status=${e.status} code=${e.code} msg=${e.message}")
    // e.g. status=404 code=NOT_FOUND msg=no such table
}
```

## API reference

### `MongrelDB`

| Method | Description |
|--------|-------------|
| `MongrelDB(url, token?, username?, password?)` | Construct a client (url defaults to `http://127.0.0.1:8453`) |
| `health()` | Check daemon health |
| `tableNames()` | List table names |
| `createTable(name, columns[, constraints])` | Create a table; returns the table id |
| `dropTable(name)` | Drop a table |
| `count(table)` | Row count |
| `put(table, cells, idempotencyKey?)` | Insert a row |
| `upsert(table, cells, updateCells?, idempotencyKey?)` | Upsert a row |
| `delete(table, rowId)` | Delete by row id |
| `deleteByPk(table, pk)` | Delete by primary key |
| `query(table)` | Start a native query |
| `sql(sql)` | Execute SQL |
| `schema()` | Full schema catalog |
| `schemaFor(table)` | Single-table descriptor |
| `compact()` | Compact all tables |
| `compactTable(table)` | Compact one table |
| `setHistoryRetentionEpochs(epochs)` | Set the history retention window |
| `historyRetentionEpochs()` | Return the configured retention window |
| `earliestRetainedEpoch()` | Return the earliest readable epoch |
| `beginTransaction()` | Start a batch |

### `QueryBuilder`

| Method | Description |
|--------|-------------|
| `where(type, params)` | Add a native condition (AND-ed) |
| `projection(columnIDs)` | Set column projection |
| `limit(limit)` | Set row limit |
| `offset(offset)` | Skip matching rows before the limit |
| `build()` | Build the request payload |
| `execute()` | Run the query |
| `truncated` | Whether the last `execute` result hit the limit |

### `Transaction`

| Method | Description |
|--------|-------------|
| `put(table, cells, returning)` | Stage an insert |
| `upsert(table, cells, updateCells?, returning)` | Stage an upsert |
| `delete(table, rowId)` | Stage a delete by row id |
| `deleteByPk(table, pk)` | Stage a delete by primary key |
| `count()` | Number of staged operations |
| `commit(idempotencyKey?)` | Commit atomically |
| `rollback()` | Discard all operations |

### Exceptions

| Exception | HTTP status | Meaning |
|-----------|-------------|---------|
| `MongrelDBException` | any | Base class for all client errors |
| `AuthException` | 401, 403 | Bad or missing credentials |
| `NotFoundException` | 404 | Missing table, schema, or resource |
| `ConflictException` | 409 | Unique, FK, check, or trigger violation (carries `code` + `opIndex`) |
| `QueryException` | 400, 5xx | Malformed query, server error, or transport failure |

All exceptions extend `MongrelDBException` and expose `status`, `code`, and
`opIndex`.

## Building and testing

The project builds with the Gradle wrapper. The test suite is a live
integration suite: it boots a real `mongreldb-server` daemon and exercises the
full client surface against it. It skips automatically when no daemon is
available, while two offline tests (health-when-unreachable and an in-process
auth-header check) always run.

```sh
# Compile and run the offline checks:
./gradlew build

# Run the live suite. The harness boots mongreldb-server itself if it can find
# the binary (in this order):
#   1. the MONGRELDB_SERVER env var (path to the server binary)
#   2. ./bin/mongreldb-server
#   3. mongreldb-server on PATH
# Or point it at an already-running daemon with MONGRELDB_URL.
MONGRELDB_URL=http://127.0.0.1:8453 ./gradlew test
```

Fetch a prebuilt server binary from the
[MongrelDB releases](https://github.com/visorcraft/MongrelDB/releases):

```sh
mkdir -p bin
curl -fsSL -o bin/mongreldb-server \
  https://github.com/visorcraft/MongrelDB/releases/download/v0.60.2/mongreldb-server-linux-x64
chmod +x bin/mongreldb-server
```

## Native embedding (Tier 1)

For in-process access with zero serialization overhead, use the `NativeDB`
class (in `com.visorcraft.mongreldb.native_mode`). It loads the JNI shim
(`libmongreldb_jni`) and runs the engine directly in the JVM - no daemon
needed.

Download the prebuilt native library from the
[MongrelDB releases](https://github.com/visorcraft/MongrelDB/releases) page
and point at it via `MONGRELDB_NATIVE_DIR`:

```sh
export MONGRELDB_NATIVE_DIR=/path/to/native/libs
```

```kotlin
import com.visorcraft.mongreldb.native_mode.NativeDB

val schemaJson = """{"tables":[{"id":1,"name":"users",...}]}"""

NativeDB.create("/path/to/dbdir", schemaJson).use { db ->
    db.sqlRows("INSERT INTO users (id, name) VALUES (1, 'alice')")
    val rows = db.sqlRows("SELECT id, name FROM users")
    val arrow = db.sqlArrow("SELECT * FROM users")
    db.migrate(migrationsJson)
}
```

The HTTP client (`MongrelDB`) remains the default for connecting to a shared
daemon. Use `NativeDB` when you want the embedded experience.

## License

Dual-licensed under the **MIT License** or the **Apache License, Version 2.0**,
at your option. See [MIT](LICENSE-MIT) OR [Apache-2.0](LICENSE-APACHE) for the full text.

`SPDX-License-Identifier: MIT OR Apache-2.0`
