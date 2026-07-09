# Authentication & Authorization

A `mongreldb-server` daemon runs in one of three modes:

1. **Open** (default) - no auth required.
2. **Bearer token** (`--auth-token <TOKEN>`) - every request must carry an
   `Authorization: Bearer <TOKEN>` header.
3. **HTTP Basic** (`--auth-users`) - every request must carry an
   `Authorization: Basic <base64(user:pass)>` header.

The Kotlin client supports all three through its constructor parameters. This
guide shows each mode, how to inspect what was sent, and how to manage users and
roles via SQL when the server is in Basic mode.

---

## Bearer token mode

Start the daemon with a token:

```sh
mongreldb-server --auth-token s3cret-token
```

Connect by passing the token as the `token` constructor argument. The token is
sent as `Authorization: Bearer ...` on every request.

```kotlin
val db = MongrelDB(
    url = "http://127.0.0.1:8453",
    token = "s3cret-token",
)

if (!db.health()) {
    // A bad/missing token surfaces as AuthException on the first call;
    // health() swallows it and returns false.
    error("daemon not reachable (bad token?)")
}
```

A missing or wrong token surfaces as `AuthException` (HTTP 401/403) on any call.
`health()` catches exceptions and returns `false`, so it is a safe probe; other
methods throw.

### Where the token comes from

Hard-coding secrets in source is bad practice. Read it from the environment:

```kotlin
val token = System.getenv("MONGRELDB_TOKEN") ?: error("MONGRELDB_TOKEN not set")
val db = MongrelDB(MongrelDB.DEFAULT_BASE_URL, token = token)
```

## Basic auth mode

Start the daemon with a users file or inline users:

```sh
mongreldb-server --auth-users
```

Connect with username and password:

```kotlin
val db = MongrelDB(
    url = "http://127.0.0.1:8453",
    username = "admin",
    password = "s3cret",
)
```

The client base64-encodes `username:password` and sets
`Authorization: Basic ...` on every request.

## Token takes precedence

If you supply both a token and Basic credentials, the token wins and Basic
credentials are ignored. This lets you layer an override without branching:

```kotlin
val db = MongrelDB(
    url = url,
    token = "overrides-everything", // token wins
    username = "fallback",          // ignored
    password = "user",              // ignored
)
```

## Timeouts

The default connect and read timeouts are both 30 seconds. Override them with
the `connectTimeoutMillis` and `readTimeoutMillis` constructor arguments:

```kotlin
val db = MongrelDB(
    url = "http://127.0.0.1:8453",
    token = token,
    connectTimeoutMillis = 10_000,
    readTimeoutMillis = 60_000,
)
```

Because the client uses `java.net.HttpURLConnection`, there is no connection
pooling to configure: each request opens and closes its own connection. For a
pooled transport, run the client behind a local HTTP proxy or adopt the JVM's
`keep.alive` defaults (on by default).

## Verifying what gets sent

The auth header is applied in `MongrelDB.applyAuth`, called from every request.
For debugging, point the client at a local echo server or watch the daemon logs.
A quick integration test pattern is in
`src/test/kotlin/.../MongrelDBLiveTest.kt`: it spins up an in-process
`com.sun.net.httpserver.HttpServer` that captures the `Authorization` header and
asserts the client sent `Bearer <token>`.

## User and role management via SQL

When the daemon is in Basic auth mode, users and roles live in the catalog and
are managed with SQL. Run these statements through `MongrelDB.sql`.

### Create a user

```kotlin
db.sql("CREATE USER alice WITH PASSWORD 'hunter2'")
```

### Alter a user

Change a password:

```kotlin
db.sql("ALTER USER alice WITH PASSWORD 'new-password'")
```

Grant the admin role:

```kotlin
db.sql("ALTER USER alice ADMIN")
```

`ALTER USER ... ADMIN` is how you promote a user to full administrative
privileges (table creation/drop, compaction, user management). Use it sparingly.

### Drop a user

```kotlin
db.sql("DROP USER alice")
```

### Roles and grants

```kotlin
db.sql("CREATE ROLE analyst")
db.sql("GRANT SELECT ON orders TO analyst")
db.sql("GRANT analyst TO alice")
db.sql("REVOKE SELECT ON orders FROM analyst")
db.sql("DROP ROLE analyst")
```

Exact grant syntax mirrors the server's SQL flavor; consult the server's SQL
reference for the full `GRANT`/`REVOKE` grammar available in your build.

## Common pitfalls

**Auth errors look like other errors without typed catches.** A 401/403 raises
`AuthException`; a 404 raises `NotFoundException`. Always catch the specific
subclass rather than string-matching `message`.

**Forgetting to set auth in production.** A client built with
`MongrelDB(url)` sends no credentials. Against an auth-enabled daemon, every
call throws `AuthException`. Centralize client construction so the auth
credentials are never accidentally dropped.

**Sharing one client across threads is fine; sharing credentials across users
is not.** A `MongrelDB` instance is thread-safe, but it carries one identity.
If you serve multiple authenticated users, build a client per user (or per
request) with that user's token.

**Token in version control.** Put secrets in the environment, a secret manager,
or a file outside the repo. Never commit a real token.

## Next steps

- [errors.md](errors.md) - `AuthException` and the rest of the exception hierarchy
- [quickstart.md](quickstart.md) - the full end-to-end walkthrough
