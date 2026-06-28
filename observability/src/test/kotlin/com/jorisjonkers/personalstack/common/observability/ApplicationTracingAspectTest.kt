package com.jorisjonkers.personalstack.common.observability

import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory
import org.springframework.stereotype.Service

class ApplicationTracingAspectTest {
    private val spans = InMemorySpanExporter.create()
    private val provider =
        SdkTracerProvider
            .builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spans))
            .build()
    private val tracer = provider.get("test")

    @AfterEach
    fun cleanup() {
        provider.shutdown()
    }

    private fun proxied(): TracedService {
        val aspect = ApplicationTracingAspect(tracer)
        val factory =
            AspectJProxyFactory(TracedService()).apply {
                addAspect(aspect)
            }
        return factory.getProxy()
    }

    @Test
    fun `auto configuration creates the tracing aspect`() {
        assertThat(ApplicationTracingAspectAutoConfiguration().applicationTracingAspect())
            .isInstanceOf(ApplicationTracingAspect::class.java)
    }

    @Test
    fun `creates a span named ClassName_method with rendered arguments`() {
        proxied().greet("world")
        val recorded = spans.finishedSpanItems
        assertThat(recorded).hasSize(1)
        assertThat(recorded[0].name).isEqualTo("TracedService.greet(world)")
        assertThat(recorded[0].status.statusCode).isEqualTo(StatusCode.UNSET)
    }

    @Test
    fun `records errors when the method throws`() {
        runCatching { proxied().boom() }
        val recorded = spans.finishedSpanItems
        assertThat(recorded).hasSize(1)
        assertThat(recorded[0].name).isEqualTo("TracedService.boom")
        assertThat(recorded[0].status.statusCode).isEqualTo(StatusCode.ERROR)
        assertThat(recorded[0].events).anySatisfy { event ->
            assertThat(event.name).isEqualTo("exception")
        }
    }

    @Test
    fun `arguments are appended to the span name and exposed as code_args`() {
        proxied().greet("world")
        val recorded = spans.finishedSpanItems.single()
        assertThat(recorded.name).isEqualTo("TracedService.greet(world)")
        assertThat(recorded.attributes.asMap()).containsEntry(
            io.opentelemetry.api.common.AttributeKey
                .stringKey("code.args"),
            "world",
        )
    }

    @Test
    fun `arguments are redacted wholesale for sensitive-looking classes`() {
        val aspect = ApplicationTracingAspect(tracer)
        val factory =
            AspectJProxyFactory(SensitiveTokenService()).apply {
                addAspect(aspect)
            }
        val proxy: SensitiveTokenService = factory.getProxy()
        proxy.createToken("alice", "s3cret")
        val recorded = spans.finishedSpanItems.single()
        assertThat(recorded.name).isEqualTo("SensitiveTokenService.createToken(redacted)")
    }

    @Test
    fun `long arguments are truncated`() {
        proxied().greet("x".repeat(200))
        val recorded = spans.finishedSpanItems.single()
        assertThat(recorded.name).startsWith("TracedService.greet(")
        assertThat(recorded.name.length).isLessThan(120)
    }

    @Test
    fun `rendered arguments include separators nulls and total truncation`() {
        proxied().combine(
            "x".repeat(40),
            null,
            "y".repeat(40),
            "z".repeat(40),
        )

        val recorded = spans.finishedSpanItems.single()
        assertThat(recorded.name).startsWith("TracedService.combine(")
        assertThat(recorded.name.length).isLessThan(120)
        assertThat(recorded.attributes.asMap()).containsKey(
            io.opentelemetry.api.common.AttributeKey
                .stringKey("code.args"),
        )
    }

    @Suppress("FunctionOnlyReturningConstant")
    @Service
    open class TracedService {
        open fun greet(name: String): String = "hello $name"

        open fun combine(
            first: String,
            second: String?,
            third: String,
            fourth: String,
        ): String = listOf(first, second, third, fourth).joinToString()

        open fun boom(): Nothing = throw IllegalStateException("nope")
    }

    @Suppress("FunctionOnlyReturningConstant")
    @Service
    open class SensitiveTokenService {
        open fun createToken(
            username: String,
            password: String,
        ): String = "token-$username"
    }
}
