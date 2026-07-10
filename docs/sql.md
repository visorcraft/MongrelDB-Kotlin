# SQL

MongrelDB ships a DataFusion-backed SQL engine at `POST /sql`. From Kotlin, run
SQL with `MongrelDB.sql`:

```kotlin
val rows = db.sql("SELECT 1")
```

This guide covers the SQL surface - DDL, DML, `CREATE TABLE AS SELECT`,
recursive CTEs, and window functions - and when to reach for SQL versus the
native query builder.

---

## How `sql` behaves

`MongrelDB.sql(sql)` sends `{"sql": "...", "format": "json"}` to `/sql`,
asking the daemon for JSON output. It returns the decoded rows when the daemon
replies with a JSON result set, and an empty list otherwise.

In practice:

- **DDL and DML** (`CREATE TABLE`, `INSERT`, `UPDATE`, `DELETE`) reply with a
  non-JSON status body. `sql` returns an empty list - success is the signal.
- **`SELECT`** returns its rows as a JSON array of row objects keyed by column
  name, so `sql` decodes them into a `List<Map<String, Any?>>`. Use the native
  `QueryBuilder` when you need conditions that map to a specialized index
  (bitmap, range, full-text, vector); use `sql` for joins, CTEs, window
  functions, and arbitrary aggregates.

Errors are mapped to the same typed exceptions as everything else: an HTTP 400
or 5xx raises `QueryException`; 409 raises `ConflictException`; and so on. See
[errors.md](errors.md).

```kotlin
try {
    db.sql("INSERT INTO orders (id, customer, amount) VALUES (99, 'Zoe', 999.0)")
} catch (e: ConflictException) {
    if (e.code == "UNIQUE_VIOLATION") {
        println("duplicate row: ${e.message}")
    }
}
```

## CREATE TABLE

Define a table in SQL instead of via `MongrelDB.createTable`. Column ids are
assigned by the server when not stated.

```kotlin
db.sql(
    """
    CREATE TABLE products (
      id          INT64 PRIMARY KEY,
      name        VARCHAR,
      price       FLOAT64,
      category    VARCHAR,
      in_stock    BOOLEAN
    )
    """.trimIndent(),
)
```

## INSERT

```kotlin
db.sql("INSERT INTO products (id, name, price, category, in_stock) VALUES (1, 'Widget', 9.99, 'tools', true)")
db.sql("INSERT INTO products VALUES (2, 'Gadget', 19.99, 'tools', true)")
```

For bulk inserts, the native batch transaction (`MongrelDB.beginTransaction`)
is usually faster because it stages ops in one round trip without re-parsing
SQL.

## UPDATE

```kotlin
db.sql("UPDATE products SET price = 14.99 WHERE id = 1")
db.sql("UPDATE orders SET amount = 200.0 WHERE customer = 'Bob'")
```

## DELETE

```kotlin
db.sql("DELETE FROM products WHERE in_stock = false")
db.sql("DELETE FROM products WHERE id = 2")
```

## SELECT

```kotlin
db.sql("SELECT id, name FROM products WHERE category = 'tools' ORDER BY price")
db.sql("SELECT category, COUNT(*) AS n FROM products GROUP BY category")
```

The client requests JSON output (`format: "json"`), so each `SELECT` returns
its rows decoded into a `List<Map<String, Any?>>` keyed by column name.

## CREATE TABLE AS SELECT

Materialize a query result into a new table. Great for snapshots, rollups, and
denormalized aggregates.

```kotlin
// Snapshot all high-value orders into a new table.
db.sql("CREATE TABLE archive AS SELECT * FROM orders WHERE amount > 500")

// Roll up sales by customer.
db.sql(
    """
    CREATE TABLE sales_by_customer AS
    SELECT customer, SUM(amount) AS total
    FROM orders
    GROUP BY customer
    """.trimIndent(),
)
```

The new table inherits column types from the query. Query it afterward with the
native builder or SQL.

## Recursive CTEs

`WITH RECURSIVE` is fully supported. Classic use cases: series generation,
hierarchy/graph traversal.

```kotlin
// Generate the numbers 1..10.
db.sql(
    """
    WITH RECURSIVE r(n) AS (
      SELECT 1
      UNION ALL
      SELECT n + 1 FROM r WHERE n < 10
    )
    SELECT n FROM r
    """.trimIndent(),
)
```

A common practical example is walking an adjacency list:

```kotlin
db.sql(
    """
    WITH RECURSIVE descendants(id) AS (
      SELECT id FROM categories WHERE id = 1
      UNION ALL
      SELECT c.id FROM categories c
      JOIN descendants d ON c.parent_id = d.id
    )
    SELECT id FROM descendants
    """.trimIndent(),
)
```

## Window functions

Window functions compute aggregates/rankings across a moving window without
collapsing rows. Useful for top-N-per-group, running totals, and row numbers.

```kotlin
// Row number within each customer, ordered by amount descending.
db.sql(
    """
    SELECT id, customer, amount,
           ROW_NUMBER() OVER (PARTITION BY customer ORDER BY amount DESC) AS rn
    FROM orders
    """.trimIndent(),
)

// Running total per customer.
db.sql(
    """
    SELECT id, customer, amount,
           SUM(amount) OVER (PARTITION BY customer ORDER BY id) AS running_total
    FROM orders
    """.trimIndent(),
)
```

`RANK()`, `DENSE_RANK()`, `LAG()`, `LEAD()`, `NTILE()`, and the usual
window-frame clauses are available through DataFusion.

## When to use SQL vs. the query builder

Both read from the same tables, but they are optimized for different jobs.

| Reach for | When |
|-----------|------|
| **`QueryBuilder`** | Point lookups, range scans, bitmap filters, full-text, and vector similarity that map to a native index. Sub-millisecond, no parser overhead, and rows decode into Kotlin maps directly. |
| **SQL** | DDL (`CREATE TABLE`, schemas, materialized views), multi-statement setup, joins, recursive CTEs, window functions, and arbitrary aggregates. Also the natural choice for admin scripts and one-off analysis. |

Rules of thumb:

- Need a typed `List<Map<String, Any?>>` of matching rows? Either works: the
  query builder for indexed lookups, or `sql` for ad-hoc queries.
- Building/dropping tables, or running a `CREATE TABLE AS SELECT`? Use SQL.
- Joining multiple tables, computing rankings, or walking a graph? Use SQL.
- Filtering by one or more indexed columns? Use the query builder - it maps
  directly to a specialized index and skips the SQL parser.

Mix freely: create tables with SQL, write rows with `MongrelDB.put`, read them
back with `QueryBuilder`, and run analytics with SQL.

## Next steps

- [queries.md](queries.md) - every native index condition in detail
- [transactions.md](transactions.md) - bulk inserts via batch transactions
- [errors.md](errors.md) - handling SQL execution errors
