package com.jorisjonkers.personalstack.common.timing

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class RequestTimingFilterTest {
    private val filter = RequestTimingFilter()
    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var slf4jLogger: Logger

    @BeforeEach
    fun attach() {
        slf4jLogger = LoggerFactory.getLogger("com.jorisjonkers.personalstack.common.timing.request") as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        slf4jLogger.addAppender(appender)
        slf4jLogger.level = Level.INFO
    }

    @AfterEach
    fun detach() {
        slf4jLogger.detachAppender(appender)
    }

    @Test
    fun `logs method path status duration queries and query_ms`() {
        val request = MockHttpServletRequest("GET", "/api/v1/auth/me")
        val response = MockHttpServletResponse().apply { status = 200 }

        filter.doFilter(request, response, MockFilterChain())

        val event = appender.list.single()
        assertThat(event.formattedMessage)
            .contains("GET /api/v1/auth/me")
            .contains("status=200")
            .contains("queries=0")
            .contains("query_ms=0")
            .containsPattern("duration_ms=\\d+")
    }

    @Test
    fun `picks up query accumulators set by the jOOQ listener during the chain`() {
        val request = MockHttpServletRequest("GET", "/api/v1/auth/me")
        val response = MockHttpServletResponse().apply { status = 200 }
        val populatingChain =
            FilterChain { req: ServletRequest, _: ServletResponse ->
                req.setAttribute(RequestTimingAttributes.QUERY_COUNT, 7)
                req.setAttribute(RequestTimingAttributes.TOTAL_QUERY_NANOS, 12_345_000_000L) // 12 345 ms
            }

        filter.doFilter(request, response, populatingChain)

        assertThat(appender.list.single().formattedMessage)
            .contains("queries=7")
            .contains("query_ms=12345")
    }

    @Test
    fun `logs even when the chain throws`() {
        val request = MockHttpServletRequest("POST", "/api/v1/auth/login")
        val response = MockHttpServletResponse().apply { status = 500 }
        val throwingChain =
            FilterChain { _: ServletRequest, _: ServletResponse ->
                throw IllegalStateException("boom")
            }

        runCatching { filter.doFilter(request, response, throwingChain) }

        val event = appender.list.single()
        assertThat(event.formattedMessage)
            .contains("POST /api/v1/auth/login")
            .contains("status=500")
    }

    @Test
    fun `appends query string when present`() {
        val request =
            MockHttpServletRequest("GET", "/api/v1/users").apply {
                queryString = "page=2&size=10"
            }
        val response = MockHttpServletResponse().apply { status = 200 }

        filter.doFilter(request, response, MockFilterChain())

        assertThat(appender.list.single().formattedMessage)
            .contains("/api/v1/users?page=2&size=10")
    }
}
