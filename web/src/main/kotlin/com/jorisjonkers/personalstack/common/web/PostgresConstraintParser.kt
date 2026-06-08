package com.jorisjonkers.personalstack.common.web

import org.springframework.dao.DataIntegrityViolationException

/**
 * Pulls structured fields off the `PSQLException` chain hanging from a
 * Spring `DataIntegrityViolationException` so the [GlobalExceptionHandler]
 * can emit a 422 with `constraint`, `column`, and `referencedTable`
 * extension members instead of a free-form 500 detail line.
 *
 * The Postgres JDBC driver exposes everything needed via
 * `ServerErrorMessage`:
 *
 * * `getConstraint()` — the constraint name (`workspaces_github_link_id_fkey`).
 * * `getDetail()`     — the localized Postgres detail line; matches
 *   one of two well-known shapes:
 *     - FK: `Key (github_link_id)=(<uuid>) is not present in table "github_links".`
 *     - UNIQUE: `Key (name)=(foo) already exists.`
 * * `getSQLState()`   — 23503 = FK violation, 23505 = unique, 23502 = not-null.
 *
 * The parser does not depend on the JDBC driver class directly — it
 * uses reflection to read `getServerErrorMessage` so this module can
 * still be loaded by services that don't pull `postgresql` on the
 * runtime classpath (only assistant-api / auth-api / knowledge-api do).
 */
internal object PostgresConstraintParser {
    private val DETAIL_FK_REGEX =
        Regex(
            """Key \(([^)]+)\)=\([^)]*\) is not present in table "([^"]+)"""",
        )
    private val DETAIL_UNIQUE_REGEX =
        Regex(
            """Key \(([^)]+)\)=\([^)]*\) already exists""",
        )

    data class ConstraintInfo(
        val sqlState: String? = null,
        val constraint: String? = null,
        val column: String? = null,
        val referencedTable: String? = null,
        val postgresDetail: String? = null,
    ) {
        fun shortMessage(): String =
            when {
                referencedTable != null -> "References a $referencedTable row that does not exist"
                sqlState == "23505" -> "Already in use"
                sqlState == "23502" -> "Required"
                else -> "Invalid value"
            }

        val detail: String?
            get() {
                val parts =
                    buildList {
                        when {
                            sqlState == "23503" && column != null && referencedTable != null ->
                                add("Field `$column` references a $referencedTable row that does not exist")
                            sqlState == "23505" && column != null ->
                                add("Field `$column` already in use")
                            sqlState == "23502" && column != null ->
                                add("Field `$column` is required")
                            column != null && constraint != null ->
                                add("Field `$column` violated constraint `$constraint`")
                            constraint != null ->
                                add("Constraint `$constraint` violated")
                        }
                        postgresDetail?.let { add(it) }
                    }
                return parts.takeIf { it.isNotEmpty() }?.joinToString(": ")
            }
    }

    fun parse(ex: DataIntegrityViolationException): ConstraintInfo {
        val cause = walkCauseChain(ex)
        if (cause == null) return ConstraintInfo()
        val sqlState = invokeStringGetter(cause, "getSQLState")
        val serverMessage = invokeGetter(cause, "getServerErrorMessage")
        val constraint = serverMessage?.let { invokeStringGetter(it, "getConstraint") }
        val detail = serverMessage?.let { invokeStringGetter(it, "getDetail") }
        val parsed = detail?.let { parseDetailLine(it) }
        return ConstraintInfo(
            sqlState = sqlState,
            constraint = constraint,
            column = parsed?.first,
            referencedTable = parsed?.second,
            postgresDetail = detail,
        )
    }

    private fun parseDetailLine(detail: String): Pair<String, String?>? {
        DETAIL_FK_REGEX.find(detail)?.let {
            return it.groupValues[1] to it.groupValues[2]
        }
        DETAIL_UNIQUE_REGEX.find(detail)?.let {
            return it.groupValues[1] to null
        }
        return null
    }

    /**
     * Find the first exception in the cause chain that *structurally*
     * looks like a `PSQLException` — i.e. exposes a public
     * `getServerErrorMessage()` method. Matching on the method shape
     * rather than the class name keeps this module free of a hard
     * dependency on `org.postgresql.util.PSQLException`, which only
     * sits on the runtime classpath of services that actually use
     * Postgres, and lets the unit tests inject a fake without
     * pulling the driver in.
     */
    private fun walkCauseChain(ex: Throwable): Throwable? {
        var current: Throwable? = ex
        repeat(MAX_CAUSE_DEPTH) {
            val frame = current ?: return null
            if (looksLikePsqlException(frame)) return frame
            current = frame.cause
        }
        return null
    }

    private fun looksLikePsqlException(target: Any): Boolean =
        runCatching { target::class.java.getMethod("getServerErrorMessage") }.isSuccess

    private fun invokeGetter(
        target: Any,
        methodName: String,
    ): Any? =
        runCatching {
            target::class.java.getMethod(methodName).invoke(target)
        }.getOrNull()

    private fun invokeStringGetter(
        target: Any,
        methodName: String,
    ): String? = invokeGetter(target, methodName) as? String

    private const val MAX_CAUSE_DEPTH = 8
}
