package com.jorisjonkers.personalstack.common.identity.principal

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PrincipalConformanceTest {
    private val identity = FakeIdentity()

    @AfterEach
    fun stop() = identity.stop()

    private fun reader(
        vector: JsonNode,
        secret: String? = null,
    ): PrincipalReader =
        PrincipalReader(
            PrincipalReaderConfig(
                issuer = vector.path("issuer").asText("https://auth.example.test"),
                clientId = vector.path("client").asText(),
                clientSecret = secret,
                keysUri = identity.keys,
                introspectUri = identity.introspect,
                clock = Clock.fixed(Instant.parse(vector.path("now").asText("2026-10-20T12:00:30Z")), ZoneOffset.UTC),
            ),
        )

    /** The Principal a vector states, in this adapter's type. */
    private fun stated(p: JsonNode): Principal =
        Principal(
            subject = p.path("subject").asText(),
            kind = Kind.entries.single { it.value == p.path("kind").asText() },
            email = p.path("email").textValue(),
            name = p.path("name").textValue(),
            roles = p.path("roles").map { it.asText() },
            via = Via.entries.single { it.value == p.path("via").asText() },
            actor =
                p
                    .path(
                        "actor",
                    ).takeIf { it.isObject }
                    ?.let { Actor(it.path("id").asText(), it.path("name").asText()) },
            twoFactor = p.path("twoFactor").asBoolean(),
            twoFactorAt = p.path("twoFactorAt").textValue()?.let { Instant.parse(it) },
            sid = p.path("sid").textValue(),
            ver = p.path("ver").asText(),
        )

    private fun readAs(
        vector: JsonNode,
        read: () -> Principal,
    ) {
        val refusal = vector.path("refusal").asText()
        if (refusal.isNotEmpty()) {
            assertThatThrownBy { read() }
                .isInstanceOf(PrincipalRefusedException::class.java)
                .extracting("reason")
                .isEqualTo(refusal)
        } else {
            assertThat(read()).isEqualTo(stated(vector.path("principal")))
        }
    }

    @TestFactory
    fun `every signed-header vector reads as the contract says`(): List<DynamicTest> =
        Contract.vectors("signed-header").map { (name, vector) ->
            DynamicTest.dynamicTest(name) {
                identity.publish(Contract.text("vectors/jwks.json"))
                readAs(vector) { reader(vector).read { vector.path("headers").path(it).textValue() } }
            }
        }

    @TestFactory
    fun `every access-token vector reads as the contract says`(): List<DynamicTest> =
        Contract.vectors("access-token").map { (name, vector) ->
            DynamicTest.dynamicTest(name) {
                identity.publish(Contract.text("vectors/jwks.json"))
                val authorization = "Bearer " + vector.path("token").asText()
                readAs(vector) { reader(vector).read { if (it == "Authorization") authorization else null } }
            }
        }

    @TestFactory
    fun `every introspection vector reads as the contract says`(): List<DynamicTest> =
        Contract.vectors("introspection").map { (name, vector) ->
            DynamicTest.dynamicTest(name) {
                identity.answer(vector.path("client").asText(), "s3cret", "opaque", vector.path("answer").toString())
                readAs(vector) {
                    reader(vector, "s3cret").read {
                        if (it ==
                            "Authorization"
                        ) {
                            "Bearer opaque"
                        } else {
                            null
                        }
                    }
                }
            }
        }

    @Test
    fun `an access token presented as the signed header is refused by typ`() {
        val vector = Contract.vectors("access-token").getValue("session")
        identity.publish(Contract.text("vectors/jwks.json"))

        assertThatThrownBy {
            reader(vector).read {
                if (it ==
                    PrincipalReader.HEADER
                ) {
                    vector.path("token").asText()
                } else {
                    null
                }
            }
        }.extracting("reason")
            .isEqualTo("wrong-typ")
    }

    @Test
    fun `a signed header presented as a bearer token is refused by typ`() {
        val vector = Contract.vectors("signed-header").getValue("session")
        identity.publish(Contract.text("vectors/jwks.json"))
        val authorization = "Bearer " + vector.path("headers").path(PrincipalReader.HEADER).asText()

        assertThatThrownBy { reader(vector).read { if (it == "Authorization") authorization else null } }
            .extracting("reason")
            .isEqualTo("wrong-typ")
    }
}
