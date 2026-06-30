package com.jorisjonkers.personalstack.common.timing

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Instant

class HandlerTimingInterceptorTest {
    private val interceptor = HandlerTimingInterceptor()
    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var slf4jLogger: Logger

    @BeforeEach
    fun attach() {
        slf4jLogger = LoggerFactory.getLogger("com.jorisjonkers.personalstack.common.timing.handler") as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        slf4jLogger.addAppender(appender)
        slf4jLogger.level = Level.INFO
    }

    @AfterEach
    fun detach() {
        slf4jLogger.detachAppender(appender)
    }

    @Test
    fun `afterCompletion logs handler method path and duration`() {
        val request = MockHttpServletRequest("GET", "/api/v1/auth/me")
        val response = MockHttpServletResponse()

        interceptor.preHandle(request, response, Any())
        Thread.sleep(5)
        interceptor.afterCompletion(request, response, Any(), null)

        val event = appender.list.single()
        assertThat(event.formattedMessage)
            .contains("[handler]")
            .contains("GET /api/v1/auth/me")
            .containsPattern("duration_ms=\\d+")
    }

    @Test
    fun `afterCompletion without preHandle does not log`() {
        val request = MockHttpServletRequest("GET", "/api/v1/auth/me")
        val response = MockHttpServletResponse()

        interceptor.afterCompletion(request, response, Any(), null)

        assertThat(appender.list).isEmpty()
    }

    @Test
    fun `still logs when handler throws`() {
        val request = MockHttpServletRequest("POST", "/api/v1/auth/login")
        val response = MockHttpServletResponse()

        interceptor.preHandle(request, response, Any())
        interceptor.afterCompletion(request, response, Any(), IllegalStateException("boom"))

        assertThat(appender.list.single().formattedMessage)
            .contains("POST /api/v1/auth/login")
    }

    @Test
    fun `postHandle records controller return checkpoint`() {
        val request = MockHttpServletRequest("GET", "/api/v1/auth/me")
        val response = MockHttpServletResponse()
        val before = Instant.now()

        interceptor.postHandle(request, response, Any(), null)

        val recorded =
            request.getAttribute(RequestTimingAttributes.HANDLER_INVOKED_INSTANT) as? Instant
        assertThat(recorded).isNotNull()
        assertThat(recorded).isAfterOrEqualTo(before)
    }
}
