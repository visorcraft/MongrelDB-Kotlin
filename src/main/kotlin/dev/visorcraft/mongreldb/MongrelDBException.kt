package dev.visorcraft.mongreldb

/**
 * Base class for all errors raised by the MongrelDB client.
 *
 * Every non-2xx response from the daemon is mapped to a typed subclass of this
 * exception. Catch [MongrelDBException] to handle any client-side failure, or
 * catch one of the specific subclasses:
 *
 * - [AuthException] - HTTP 401/403 (bad or missing credentials)
 * - [NotFoundException] - HTTP 404 (missing table, schema, etc.)
 * - [ConflictException] - HTTP 409 (unique, foreign-key, check, or trigger
 *   constraint violations)
 * - [QueryException] - HTTP 400 or 5xx, and any other request-level failure not
 *   covered by the more specific subclasses
 *
 * Each typed exception carries the HTTP status code and the daemon's decoded
 * error envelope (message, structured code, and offending op index), so callers
 * can both branch on type and inspect the response detail.
 *
 * @property status HTTP status code returned by the daemon, or `-1` when unknown
 *                 (a client-side transport or decode failure).
 * @property code The server's structured error code, when present (e.g.
 *                `UNIQUE_VIOLATION`), or `null`.
 * @property opIndex The offending operation index within a transaction, when the
 *                  server reports one, or `null`.
 */
open class MongrelDBException : RuntimeException {
    val status: Int
    val code: String?
    val opIndex: Int?

    constructor(message: String) : this(message, -1, null, null, null)

    constructor(message: String, cause: Throwable?) : this(message, -1, null, null, cause)

    constructor(
        message: String,
        status: Int,
        code: String?,
        opIndex: Int?,
    ) : this(message, status, code, opIndex, null)

    internal constructor(
        message: String,
        status: Int,
        code: String?,
        opIndex: Int?,
        cause: Throwable?,
    ) : super(message, cause) {
        this.status = status
        this.code = code
        this.opIndex = opIndex
    }
}
