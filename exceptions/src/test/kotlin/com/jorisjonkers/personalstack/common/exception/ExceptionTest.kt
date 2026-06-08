package com.jorisjonkers.personalstack.common.exception

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExceptionTest {
    @Test
    fun `DomainException has message and code`() {
        val ex = DomainException("Something went wrong", "SOME_CODE")
        assertThat(ex.message).isEqualTo("Something went wrong")
        assertThat(ex.code).isEqualTo("SOME_CODE")
        assertThat(ex.cause).isNull()
    }

    @Test
    fun `DomainException with cause`() {
        val cause = IllegalStateException("root cause")
        val ex = DomainException("Wrapped", "WRAP_CODE", cause)
        assertThat(ex.message).isEqualTo("Wrapped")
        assertThat(ex.code).isEqualTo("WRAP_CODE")
        assertThat(ex.cause).isEqualTo(cause)
    }

    @Test
    fun `DomainException is a RuntimeException`() {
        val ex = DomainException("msg", "CODE")
        assertThat(ex).isInstanceOf(RuntimeException::class.java)
    }

    @Test
    fun `DomainException can be thrown and caught`() {
        assertThatThrownBy {
            throw DomainException("fail", "FAIL_CODE")
        }.isInstanceOf(DomainException::class.java)
            .hasMessage("fail")
    }

    @Test
    fun `NotFoundException has correct message format`() {
        val ex = NotFoundException("User", "42")
        assertThat(ex.message).isEqualTo("User not found: 42")
        assertThat(ex.code).isEqualTo("NOT_FOUND")
    }

    @Test
    fun `NotFoundException is a DomainException`() {
        val ex = NotFoundException("Order", "abc-123")
        assertThat(ex).isInstanceOf(DomainException::class.java)
        assertThat(ex).isInstanceOf(RuntimeException::class.java)
    }

    @Test
    fun `NotFoundException can be thrown and caught as DomainException`() {
        assertThatThrownBy {
            throw NotFoundException("Product", "999")
        }.isInstanceOf(DomainException::class.java)
            .hasMessage("Product not found: 999")
    }

    @Test
    fun `DomainException subclass preserves code`() {
        val ex: DomainException = NotFoundException("Item", "5")
        assertThat(ex.code).isEqualTo("NOT_FOUND")
    }
}
