package com.warehouse.stockscanner.excel

/**
 * Raised when a working file could not actually be written to disk (e.g. the
 * file is unavailable, there is no free space, or a permission error). The
 * in-memory data (Room) is never touched by a save, so nothing is lost when
 * this is thrown — only the physical Excel file lagged behind. Callers must
 * never report a save as successful when this is thrown.
 */
class ExcelSaveException(message: String, cause: Throwable? = null) : Exception(message, cause)
