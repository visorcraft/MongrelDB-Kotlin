package dev.visorcraft.mongreldb

/**
 * Raised for HTTP 404 responses - a missing table, schema, or other resource.
 */
class NotFoundException internal constructor(
    message: String,
    status: Int,
    code: String?,
    opIndex: Int?,
) : MongrelDBException(message, status, code, opIndex)
