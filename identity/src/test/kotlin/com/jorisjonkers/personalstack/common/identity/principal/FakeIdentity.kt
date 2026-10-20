package com.jorisjonkers.personalstack.common.identity.principal

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/** identity's keys and introspection endpoints, answering what a test sets, and counting the questions. */
class FakeIdentity {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    @Volatile var keysBody: String = """{"keys":[]}"""

    @Volatile var keysStatus: Int = 200

    @Volatile var introspectStatus: Int = 200

    @Volatile private var answer: String = """{"active":false}"""

    @Volatile private var credentials: String = ""

    @Volatile private var token: String = ""

    val keysFetched = AtomicInteger()
    val asked = AtomicInteger()

    init {
        server.createContext("/oidc/keys") { exchange ->
            keysFetched.incrementAndGet()
            exchange.reply(keysStatus, keysBody)
        }
        server.createContext("/oidc/introspect") { exchange ->
            asked.incrementAndGet()
            val form = String(exchange.requestBody.readAllBytes())
            val askedFor = URLDecoder.decode(form.removePrefix("token="), StandardCharsets.UTF_8)
            when {
                introspectStatus != 200 -> exchange.reply(introspectStatus, "")
                exchange.requestHeaders.getFirst(
                    "Authorization",
                ) != credentials ||
                    askedFor != token -> exchange.reply(401, "")
                else -> exchange.reply(200, answer)
            }
        }
        server.start()
    }

    private val base = URI.create("http://127.0.0.1:${server.address.port}")
    val keys: URI = base.resolve("/oidc/keys")
    val introspect: URI = base.resolve("/oidc/introspect")

    fun publish(jwks: String) {
        keysBody = jwks
    }

    /** Answers introspection about token, asked by clientId with secret, with answer. */
    fun answer(
        clientId: String,
        secret: String,
        token: String,
        answer: String,
    ) {
        this.credentials = "Basic " + Base64.getEncoder().encodeToString("$clientId:$secret".toByteArray())
        this.token = token
        this.answer = answer
    }

    fun stop() = server.stop(0)

    private fun HttpExchange.reply(
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray()
        sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) }
        close()
    }
}
