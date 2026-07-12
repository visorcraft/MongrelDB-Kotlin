package com.visorcraft.mongreldb

/**
 * Raised for HTTP 400 or 5xx responses, and for any other request-level failure
 * not covered by [AuthException], [NotFoundException], or [ConflictException].
 *
 * This is the catch-all for malformed queries, server-side errors, and transport
 * failures. A transport failure (for example an [java.net.IOException] from the
 * HTTP layer, or an interrupted thread) carries the underlying throwable as the
 * [cause] and reports a [status] of `-1`.
 */
class QueryException : MongrelDBException {
    internal constructor(message: String) : super(message)

    internal constructor(message: String, cause: Throwable?) : super(message, cause)

    internal constructor(
        message: String,
        status: Int,
        code: String?,
        opIndex: Int?,
    ) : super(message, status, code, opIndex)
}
