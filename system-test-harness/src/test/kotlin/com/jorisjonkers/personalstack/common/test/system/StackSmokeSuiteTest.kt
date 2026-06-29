package com.jorisjonkers.personalstack.common.test.system

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress

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

    private fun healthServer(status: Int): HttpServer {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/ready") { exchange ->
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        servers += server
        return server
    }

    private fun HttpServer.uri() = java.net.URI.create("http://127.0.0.1:${address.port}")
}
