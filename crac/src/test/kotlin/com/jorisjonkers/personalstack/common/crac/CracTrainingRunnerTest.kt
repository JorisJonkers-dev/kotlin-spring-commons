package com.jorisjonkers.personalstack.common.crac

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.web.server.WebServer
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.boot.web.server.context.WebServerInitializedEvent
import org.springframework.context.support.GenericApplicationContext
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class CracTrainingRunnerTest {
    private lateinit var server: HttpServer
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private var port = 0

    @BeforeEach
    fun start() {
        hits.clear()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        listOf("/foo", "/bar", "/secure").forEach { path ->
            server.createContext(path) { exchange ->
                hits.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
                val status = if (path == "/secure") 401 else 200
                exchange.sendResponseHeaders(status, -1)
                exchange.responseBody.close()
            }
        }
        server.start()
        port = server.address.port
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    @Test
    fun `warmup hits each endpoint configured number of times then triggers checkpoint`() {
        val checkpoints = AtomicInteger()
        val runner =
            CracTrainingRunner(
                properties =
                    CracTrainingProperties(
                        enabled = true,
                        endpoints = listOf("/foo", "/bar"),
                        iterations = 7,
                        checkpointAfterWarmup = true,
                    ),
                checkpointInvoker = { checkpoints.incrementAndGet() },
            )

        runner.runOnce(port)

        assertThat(hits["/foo"]?.get()).isEqualTo(7)
        assertThat(hits["/bar"]?.get()).isEqualTo(7)
        assertThat(checkpoints.get()).isEqualTo(1)
    }

    @Test
    fun `non-2xx responses do not abort warmup or skip checkpoint`() {
        val checkpoints = AtomicInteger()
        val runner =
            CracTrainingRunner(
                properties =
                    CracTrainingProperties(
                        enabled = true,
                        endpoints = listOf("/secure"),
                        iterations = 3,
                        checkpointAfterWarmup = true,
                    ),
                checkpointInvoker = { checkpoints.incrementAndGet() },
            )

        runner.runOnce(port)

        assertThat(hits["/secure"]?.get()).isEqualTo(3)
        assertThat(checkpoints.get()).isEqualTo(1)
    }

    @Test
    fun `checkpoint is skipped when checkpointAfterWarmup is false`() {
        val checkpoints = AtomicInteger()
        val runner =
            CracTrainingRunner(
                properties =
                    CracTrainingProperties(
                        enabled = true,
                        endpoints = listOf("/foo"),
                        iterations = 2,
                        checkpointAfterWarmup = false,
                    ),
                checkpointInvoker = { checkpoints.incrementAndGet() },
            )

        runner.runOnce(port)

        assertThat(hits["/foo"]?.get()).isEqualTo(2)
        assertThat(checkpoints.get()).isZero()
    }

    @Test
    fun `runOnce is a no-op when port is unresolved`() {
        val checkpoints = AtomicInteger()
        val runner =
            CracTrainingRunner(
                properties =
                    CracTrainingProperties(
                        enabled = true,
                        endpoints = listOf("/foo"),
                        iterations = 5,
                    ),
                checkpointInvoker = { checkpoints.incrementAndGet() },
            )

        runner.runOnce(0)

        assertThat(hits).isEmpty()
        assertThat(checkpoints.get()).isZero()
    }

    @Test
    fun `runOnce is a no-op when endpoint list is empty`() {
        val checkpoints = AtomicInteger()
        val runner =
            CracTrainingRunner(
                properties =
                    CracTrainingProperties(
                        enabled = true,
                        endpoints = emptyList(),
                        iterations = 5,
                    ),
                checkpointInvoker = { checkpoints.incrementAndGet() },
            )

        runner.runOnce(port)

        assertThat(hits).isEmpty()
        assertThat(checkpoints.get()).isZero()
    }

    @Test
    fun `application ready event warms resolved web server port`() {
        val checkpoints = AtomicInteger()
        val runner =
            CracTrainingRunner(
                properties =
                    CracTrainingProperties(
                        enabled = true,
                        endpoints = listOf("/foo"),
                        iterations = 4,
                    ),
                checkpointInvoker = { checkpoints.incrementAndGet() },
            )

        runner.onWebServerInitialized(TestWebServerInitializedEvent(port))
        runner.onApplicationEvent(
            ApplicationReadyEvent(
                SpringApplication(),
                emptyArray(),
                GenericApplicationContext(),
                Duration.ZERO,
            ),
        )

        assertThat(hits["/foo"]?.get()).isEqualTo(4)
        assertThat(checkpoints.get()).isEqualTo(1)
    }

    private class TestWebServerInitializedEvent(
        port: Int,
    ) : WebServerInitializedEvent(TestWebServer(port)) {
        override fun getApplicationContext(): WebServerApplicationContext = throw UnsupportedOperationException()
    }

    private class TestWebServer(
        private val serverPort: Int,
    ) : WebServer {
        override fun start() = Unit

        override fun stop() = Unit

        override fun getPort(): Int = serverPort
    }
}
