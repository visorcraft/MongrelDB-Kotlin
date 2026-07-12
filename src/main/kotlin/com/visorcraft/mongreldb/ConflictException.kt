package com.visorcraft.mongreldb

/**
 * Raised for HTTP 409 responses - a unique, foreign-key, check, or trigger
 * constraint violation.
 *
 * During a transaction commit, the engine enforces all constraints at commit
 * time. On any violation every staged operation rolls back and this exception is
 * thrown carrying the server's structured [code] (e.g. `UNIQUE_VIOLATION`,
 * `FK_VIOLATION`) and the offending [opIndex] within the batch.
 */
class ConflictException internal constructor(
    message: String,
    status: Int,
    code: String?,
    opIndex: Int?,
) : MongrelDBException(message, status, code, opIndex)
