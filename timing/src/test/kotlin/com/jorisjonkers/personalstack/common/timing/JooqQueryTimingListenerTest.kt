package com.jorisjonkers.personalstack.common.timing

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.jooq.ExecuteContext
import org.jooq.ExecuteType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

class JooqQueryTimingListenerTest {
    private val listener = JooqQueryTimingListener()
    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var slf4jLogger: Logger
    private val store = mutableMapOf<Any, Any?>()

    @BeforeEach
    fun attach() {
        slf4jLogger = LoggerFactory.getLogger("com.jorisjonkers.personalstack.common.timing.jooq") as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        slf4jLogger.addAppender(appender)
        slf4jLogger.level = Level.INFO
        store.clear()
    }

    @AfterEach
    fun detach() {
        slf4jLogger.detachAppender(appender)
        RequestContextHolder.resetRequestAttributes()
    }

    private fun mockCtx(
        sql: String?,
        type: ExecuteType = ExecuteType.READ,
    ): ExecuteContext {
        val ctx = mockk<ExecuteContext>(relaxed = true)
        every { ctx.data(any<Any>(), any<Any>()) } answers {
            val (k, v) = invocation.args
            store[k!!] = v
            null
        }
        every { ctx.data(any<Any>()) } answers { store[firstArg()] }
        every { ctx.sql() } returns sql
        every { ctx.type() } returns type
        return ctx
    }

    @Test
    fun `executeEnd logs duration_ms and sql excerpt`() {
        val ctx = mockCtx("SELECT * FROM app_user WHERE id = ?")

        listener.executeStart(ctx)
        Thread.sleep(5)
        listener.executeEnd(ctx)

        val event = appender.list.single()
        assertThat(event.formattedMessage)
            .contains("type=READ")
            .contains("SELECT * FROM app_user")
            .containsPattern("duration_ms=\\d+")
    }

    @Test
    fun `executeEnd truncates sql to 200 chars`() {
        val longSql = "SELECT " + "col, ".repeat(80) + "id FROM x"
        val ctx = mockCtx(longSql)

        listener.executeStart(ctx)
        listener.executeEnd(ctx)

        val truncated =
            appender.list
                .single()
                .formattedMessage
                .substringAfter("sql=")
        assertThat(truncated).hasSizeLessThanOrEqualTo(200)
    }

    @Test
    fun `executeEnd without prior executeStart does not log`() {
        val ctx = mockCtx("SELECT 1")

        listener.executeEnd(ctx)

        assertThat(appender.list).isEmpty()
    }

    @Test
    fun `null sql is logged as placeholder`() {
        val ctx = mockCtx(null)

        listener.executeStart(ctx)
        listener.executeEnd(ctx)

        assertThat(appender.list.single().formattedMessage).contains("sql=<no sql>")
    }

    @Test
    fun `accumulates query count and total nanos onto current request scope`() {
        val request = MockHttpServletRequest("GET", "/api/v1/auth/me")
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))

        val ctx1 = mockCtx("SELECT 1")
        val ctx2 = mockCtx("SELECT 2")

        listener.executeStart(ctx1)
        listener.executeEnd(ctx1)
        listener.executeStart(ctx2)
        listener.executeEnd(ctx2)

        assertThat(request.getAttribute(RequestTimingAttributes.QUERY_COUNT)).isEqualTo(2)
        val totalNanos = request.getAttribute(RequestTimingAttributes.TOTAL_QUERY_NANOS) as Long
        assertThat(totalNanos).isGreaterThan(0)
    }

    @Test
    fun `executeEnd outside a request scope still logs but does not crash`() {
        // No RequestContextHolder set up.
        val ctx = mockCtx("SELECT 1")

        listener.executeStart(ctx)
        listener.executeEnd(ctx)

        assertThat(appender.list).hasSize(1)
    }
}
