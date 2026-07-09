# Queries

The fluent `QueryBuilder` pushes conditions down to MongrelDB's native indexes
for sub-millisecond lookups - bitmap, learned-range, FM-index full text, HNSW
vector similarity, and more. Each condition type maps to one specialized index;
conditions are AND-ed together.

```kotlin
val q = db.query("orders")
    .where("range", mapOf("column" to 3L, "min" to 100.0, "max" to 500.0))
    .projection(listOf(1L, 2L))
    .limit(100)
val rows = q.execute()
```

This guide covers every condition type, projection, limits and truncation,
combining conditions, and the friendly aliases the builder translates for you.

---

## The basics

Every query starts with `MongrelDB.query(table)` and ends with `execute()`:

| Method | Purpose |
|--------|---------|
| `where(condType, params)` | Add a native condition. Multiple `where` calls are AND-ed. |
| `projection(columnIDs)` | Return only these column ids (`null` means all columns). |
| `limit(n)` | Cap the number of rows. |
| `build()` | Produce the request payload (useful for debugging). |
| `execute()` | Send and decode. Records the `truncated` flag. |
| `truncated` | Whether the last `execute` hit the limit. |

The request body produced by `build()` matches the daemon's `/kit/query` shape:

```json
{
  "table": "orders",
  "conditions": [{"range": {"column_id": 3, "lo": 100.0, "hi": 500.0}}],
  "projection": [1, 2],
  "limit": 100
}
```

## Condition types

`params` is a `Map<String, Any?>`. Column references use the numeric **column
id** (`Long`), never the column name. Always suffix integer literals with `L`.

### `pk` - exact primary-key match

The fastest lookup. `value` is the primary-key value.

```kotlin
db.query("orders")
    .where("pk", mapOf("value" to 42L))
    .execute()
```

### `range` - integer range (learned-range index)

Inclusive bounds. Omit `lo` or `hi` for an open range.

```kotlin
db.query("orders")
    .where(
        "range",
        mapOf(
            "column" to 3L,   // column id
            "min" to 100L,
            "max" to 500L,
        ),
    ).execute()

// Open-ended: amount >= 100
db.query("orders")
    .where("range", mapOf("column" to 3L, "min" to 100L))
    .execute()
```

### `range_f64` - float range with inclusive/exclusive control

Adds `lo_inclusive` / `hi_inclusive` flags (default inclusive).

```kotlin
db.query("orders")
    .where(
        "range_f64",
        mapOf(
            "column" to 3L,
            "min" to 100.0,
            "max" to 500.0,
            "min_inclusive" to true,
            "max_inclusive" to false,
        ),
    ) // (100.0, 500.0]
    .execute()
```

### `bitmap_eq` - equality on a bitmap-indexed column

Best for low-cardinality columns (status, category, booleans).

```kotlin
db.query("orders")
    .where("bitmap_eq", mapOf("column" to 2L, "value" to "Alice"))
    .execute()
```

### `bitmap_in` - IN predicate on a bitmap-indexed column

Match any of a set of values.

```kotlin
db.query("orders")
    .where(
        "bitmap_in",
        mapOf(
            "column" to 2L,
            "values" to listOf("Alice", "Bob", "Carol"),
        ),
    ).execute()
```

### `is_null` / `is_not_null` - null checks

```kotlin
db.query("orders").where("is_null", mapOf("column" to 3L)).execute()
db.query("orders").where("is_not_null", mapOf("column" to 3L)).execute()
```

### `fm_contains` - full-text substring search (FM-index)

Substring match within a column. Use `pattern` (the server key) or the friendly
`value` alias - both translate to `pattern` on the wire for FTS conditions.

```kotlin
db.query("documents")
    .where(
        "fm_contains",
        mapOf(
            "column" to 2L,
            "pattern" to "database performance",
        ),
    ).limit(10).execute()

// Friendly alias: "value" -> "pattern" for fm_contains only.
db.query("documents")
    .where("fm_contains", mapOf("column" to 2L, "value" to "database"))
    .execute()
```

### `fm_contains_all` - multiple substrings, all must match

```kotlin
db.query("documents")
    .where(
        "fm_contains_all",
        mapOf(
            "column" to 2L,
            "patterns" to listOf("database", "performance"),
        ),
    ).execute()
```

### `ann` - dense vector similarity (HNSW)

Approximate nearest-neighbors over a `float` vector column. `k` is the result
count. Pass the query vector as a `List<Double>`, `DoubleArray`, or
`FloatArray`.

```kotlin
db.query("embeddings")
    .where(
        "ann",
        mapOf(
            "column" to 2L,
            "query" to doubleArrayOf(0.1, 0.2, 0.3, 0.4),
            "k" to 10L,
        ),
    ).execute()
```

### `sparse_match` - sparse vector match

For sparse/bag-of-words vectors.

```kotlin
db.query("docs")
    .where(
        "sparse_match",
        mapOf(
            "column" to 2L,
            "query" to mapOf(0L to 1.0, 7L to 0.5, 42L to 2.0),
            "k" to 10L,
        ),
    ).execute()
```

### `min_hash_similar` - MinHash similarity

Near-duplicate detection via MinHash signatures.

```kotlin
db.query("pages")
    .where(
        "min_hash_similar",
        mapOf(
            "column" to 2L,
            "query" to listOf(12L, 99L, 421L, 7L),
            "k" to 5L,
        ),
    ).execute()
```

## Projection (column selection)

`projection(listOf(...))` restricts the columns in each returned row. Pass
`null` (or skip the call) for all columns. Projecting to only the columns you
need cuts bandwidth and decode cost.

```kotlin
// Return only the id and customer columns.
db.query("orders")
    .where("range", mapOf("column" to 3L, "min" to 100L))
    .projection(listOf(1L, 2L))
    .execute()
```

Returned rows are `Map<String, Any?>` keyed by the column id as a JSON-decoded
key (a string like `"2"`). Cast accordingly:

```kotlin
val rows = db.query("orders")
    .projection(listOf(1L, 2L))
    .execute()
for (r in rows) {
    val customer = r["2"] // likely a String
    println(customer)
}
```

## Limit and the truncated flag

`limit(n)` caps the result. When the server has more matches than the limit
allows, it returns the first `n` and sets `truncated: true`. Read it with the
`truncated` property **after** `execute`.

```kotlin
val q = db.query("orders")
    .where("range", mapOf("column" to 3L, "min" to 0L))
    .limit(100)
val rows = q.execute()
if (q.truncated) {
    // 100 rows came back but more exist on the server. Either raise the limit,
    // page with a range predicate on the PK, or accept the cap.
    println("result capped at ${rows.size}; more rows available")
}
```

`truncated` returns `false` until `execute` has run, so build a fresh query for
each independent lookup.

## Multiple AND conditions

Chain `where` calls. Every condition must match; the server intersects the index
results.

```kotlin
// Customer is Alice AND amount is between 100 and 500.
db.query("orders")
    .where("bitmap_eq", mapOf("column" to 2L, "value" to "Alice"))
    .where("range", mapOf("column" to 3L, "min" to 100L, "max" to 500L))
    .projection(listOf(1L, 3L))
    .limit(50)
    .execute()
```

Because each `where` targets a different specialized index, the engine can pick
the most selective one to drive the lookup and intersect the rest.

## Friendly alias translation

The builder accepts readable parameter names and translates them to the server's
canonical on-wire keys. Both spellings work, so use whichever is clearer in
context.

| You write | Sent as | Applies to |
|-----------|---------|------------|
| `column` | `column_id` | all condition types |
| `min` | `lo` | `range`, `range_f64` |
| `max` | `hi` | `range`, `range_f64` |
| `min_inclusive` | `lo_inclusive` | `range_f64` |
| `max_inclusive` | `hi_inclusive` | `range_f64` |
| `value` | `pattern` | `fm_contains`, `fm_contains_all` only |

The `value` -> `pattern` alias applies **only** to FTS conditions, because `pk`
and `bitmap_eq` use `value` as their canonical key. For those, write `value`
directly.

```kotlin
// pk: "value" stays "value" (canonical)
.where("pk", mapOf("value" to 42L))

// fm_contains: "value" is translated to "pattern"
.where("fm_contains", mapOf("column" to 2L, "value" to "search term"))
// equivalent to:
.where("fm_contains", mapOf("column_id" to 2L, "pattern" to "search term"))
```

## Putting it together

A realistic combined lookup - bitmap equality + range + projection + limit +
truncation check:

```kotlin
fun topSpenders(customer: String): List<Map<String, Any?>> {
    val q = db.query("orders")
        .where("bitmap_eq", mapOf("column" to 2L, "value" to customer))
        .where("range", mapOf("column" to 3L, "min" to 100L))
        .projection(listOf(1L, 3L))
        .limit(50)
    val rows = q.execute()
    if (q.truncated) {
        System.err.println("warning: topSpenders result capped at 50")
    }
    return rows
}
```

For arbitrary predicates, joins, and aggregations that the native indexes do not
cover, use SQL instead - see [sql.md](sql.md).
