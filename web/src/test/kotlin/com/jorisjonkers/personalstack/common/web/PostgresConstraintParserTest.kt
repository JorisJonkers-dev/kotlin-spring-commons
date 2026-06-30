package com.jorisjonkers.personalstack.common.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException

class PostgresConstraintParserTest {
    @Test
    fun `constraint info describes not null violations as required fields`() {
        val info = PostgresConstraintParser.ConstraintInfo(sqlState = "23502", column = "email")

        assertThat(info.shortMessage()).isEqualTo("Required")
        assertThat(info.detail).isEqualTo("Field `email` is required")
    }

    @Test
    fun `constraint info describes generic column constraints`() {
        val info =
            PostgresConstraintParser.ConstraintInfo(
                sqlState = "23514",
                constraint = "widgets_quantity_check",
                column = "quantity",
            )

        assertThat(info.shortMessage()).isEqualTo("Invalid value")
        assertThat(info.detail).isEqualTo("Field `quantity` violated constraint `widgets_quantity_check`")
    }

    @Test
    fun `constraint info describes constraint-only violations`() {
        val info =
            PostgresConstraintParser.ConstraintInfo(
                sqlState = "23514",
                constraint = "widgets_account_id_name_key",
            )

        assertThat(info.detail).isEqualTo("Constraint `widgets_account_id_name_key` violated")
    }

    @Test
    fun `parser keeps unmatched postgres detail without a parsed column`() {
        val cause =
            FakePsqlException(
                serverErrorMessage =
                    FakeServerErrorMessage(
                        constraint = "widgets_quantity_check",
                        detail = "Failing row contains (1, -1).",
                    ),
                sqlState = "23514",
            )
        val exception = DataIntegrityViolationException("check violation", cause)

        val info = PostgresConstraintParser.parse(exception)

        assertThat(info.constraint).isEqualTo("widgets_quantity_check")
        assertThat(info.column).isNull()
        assertThat(info.detail).isEqualTo(
            "Constraint `widgets_quantity_check` violated: Failing row contains (1, -1).",
        )
    }

    @Test
    fun `parser stops scanning after maximum cause depth`() {
        val exception = DataIntegrityViolationException("deep chain", causeChainWithoutPostgresShape())

        val info = PostgresConstraintParser.parse(exception)

        assertThat(info.constraint).isNull()
        assertThat(info.detail).isNull()
    }

    private fun causeChainWithoutPostgresShape(): Throwable {
        var current: Throwable = RuntimeException("leaf")
        repeat(8) { index ->
            current = RuntimeException("frame-$index", current)
        }
        return current
    }

    @Suppress("unused", "MemberVisibilityCanBePrivate")
    private class FakePsqlException(
        message: String? = "fake psql exception",
        private val serverErrorMessage: FakeServerErrorMessage? = null,
        private val sqlState: String? = null,
    ) : RuntimeException(message) {
        fun getServerErrorMessage(): FakeServerErrorMessage? = serverErrorMessage

        fun getSQLState(): String? = sqlState
    }

    @Suppress("unused", "MemberVisibilityCanBePrivate")
    private class FakeServerErrorMessage(
        private val constraint: String? = null,
        private val detail: String? = null,
    ) {
        fun getConstraint(): String? = constraint

        fun getDetail(): String? = detail
    }
}
