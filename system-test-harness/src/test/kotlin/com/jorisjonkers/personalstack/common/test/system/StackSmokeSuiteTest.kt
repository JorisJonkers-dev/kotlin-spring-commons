package com.jorisjonkers.personalstack.common.test.system

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.Authenticator
import java.net.CookieHandler
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

class StackSmokeSuiteTest {
    private val servers = mutableListOf<HttpServer>()

    @AfterEach
    fun stopServers() {
        servers.forEach { it.stop(0) }
    }

    @Test
    fun `runs configured HTTP checks`() {
        val server = healthServer(status = 204)
        val target = StackTarget(mapOf("api" to server.uri()))

        val result =
            StackSmokeSuite(
                target = target,
                checks = listOf(StackSmokeCheck(service = "api", path = "/ready")),
            ).run()

        val check = result.checks.single()
        assertThat(check.service).isEqualTo("api")
        assertThat(check.statusCode).isEqualTo(204)
        assertThat(check.successful).isTrue()
    }

    @Test
    fun `runs default HTTP checks for configured targets`() {
        val server = healthServer(status = 204, path = "/health")
        val target = StackTarget(mapOf("api" to server.uri()))

        val result = StackSmokeSuite(target).run()

        val check = result.checks.single()
        assertThat(check.service).isEqualTo("api")
        assertThat(check.uri).isEqualTo(java.net.URI.create("${server.uri()}/health"))
        assertThat(check.successful).isTrue()
    }

    @Test
    fun `fails when smoke check status is outside expected range`() {
        val server = healthServer(status = 503)
        val target = StackTarget(mapOf("api" to server.uri()))

        assertThatIllegalStateException()
            .isThrownBy {
                StackSmokeSuite(
                    target = target,
                    checks = listOf(StackSmokeCheck(service = "api", path = "/ready")),
                ).run()
            }.withMessageContaining("returned 503")
    }

    @Test
    fun `validates required image tags before running checks`() {
        val server = healthServer(status = 204)
        val target = StackTarget(mapOf("api" to server.uri()))

        assertThatIllegalArgumentException()
            .isThrownBy {
                StackSmokeSuite(
                    target = target,
                    images = ImageTags.parse(""),
                    checks = listOf(StackSmokeCheck(service = "api", path = "/ready")),
                    requiredImageServices = setOf("api"),
                ).run()
            }
    }

    @Test
    fun `records interrupted HTTP failures and restores interrupt flag`() {
        val target = StackTarget(mapOf("api" to java.net.URI.create("http://example.test")))

        try {
            assertThatIllegalStateException()
                .isThrownBy {
                    StackSmokeSuite(
                        target = target,
                        checks = listOf(StackSmokeCheck(service = "api")),
                        client = ThrowingHttpClient(InterruptedException("interrupted")),
                    ).run()
                }.withMessageContaining("api http://example.test/health returned no response: interrupted")
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
    }

    private fun healthServer(
        status: Int,
        path: String = "/ready",
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        servers += server
        return server
    }

    private fun HttpServer.uri() = java.net.URI.create("http://127.0.0.1:${address.port}")

    private class ThrowingHttpClient(
        private val exception: Exception,
    ) : HttpClient() {
        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

        override fun connectTimeout(): Optional<Duration> = Optional.empty()

        override fun followRedirects(): Redirect = Redirect.NEVER

        override fun proxy(): Optional<ProxySelector> = Optional.empty()

        override fun sslContext(): SSLContext = SSLContext.getDefault()

        override fun sslParameters(): SSLParameters = SSLParameters()

        override fun authenticator(): Optional<Authenticator> = Optional.empty()

        override fun version(): Version = Version.HTTP_1_1

        override fun executor(): Optional<Executor> = Optional.empty()

        @Throws(IOException::class, InterruptedException::class)
        override fun <T> send(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): HttpResponse<T> {
            throw exception
        }

        override fun <T> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = CompletableFuture.failedFuture(UnsupportedOperationException("not used"))

        override fun <T> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = CompletableFuture.failedFuture(UnsupportedOperationException("not used"))
    }
}
