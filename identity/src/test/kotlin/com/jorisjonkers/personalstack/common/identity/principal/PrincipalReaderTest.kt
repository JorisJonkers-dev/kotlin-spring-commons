package com.jorisjonkers.personalstack.common.identity.principal

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.util.JSONObjectUtils
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock a test moves. */
class MovingClock(
    var now: Instant,
) : Clock() {
    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this
}

class PrincipalReaderTest {
    private val identity = FakeIdentity()
    private val signedAt = Instant.parse("2026-10-20T12:00:00Z")
    private val clock = MovingClock(signedAt.plusSeconds(30))
    private val key: RSAKey = RSAKeyGenerator(2048).keyID("k").generate()

    @AfterEach
    fun stop() = identity.stop()

    private fun reader(secret: String? = "s3cret"): PrincipalReader =
        PrincipalReader(
            PrincipalReaderConfig(
                issuer = "https://auth.example.test",
                clientId = "web",
                clientSecret = secret,
                keysUri = identity.keys,
                introspectUri = identity.introspect,
                clock = clock,
            ),
        )

    private fun claims(edit: (MutableMap<String, Any?>) -> Unit = {}): MutableMap<String, Any?> =
        mutableMapOf<String, Any?>(
            "iss" to "https://auth.example.test",
            "sub" to "ada",
            "aud" to "web",
            "client_id" to "web",
            "exp" to signedAt.plusSeconds(60).epochSecond,
            "ver" to "1.1",
            "kind" to "user",
            "via" to "personal-token",
            "roles" to listOf("user"),
            "two_factor" to false,
        ).also(edit)

    private fun token(
        claims: Map<String, Any?>,
        typ: String = "JWT",
    ): String {
        identity.publish(JWKSet(key.toPublicJWK()).toString())
        val header =
            JWSHeader
                .Builder(JWSAlgorithm.RS256)
                .keyID("k")
                .type(JOSEObjectType(typ))
                .build()
        return JWSObject(header, Payload(claims)).apply { sign(RSASSASigner(key)) }.serialize()
    }

    private fun readBearer(
        r: PrincipalReader,
        token: String,
    ): Principal = r.read { if (it == "Authorization") "Bearer $token" else null }

    private fun refusedAs(
        reason: String,
        read: () -> Any,
    ) {
        assertThatThrownBy { read() }
            .isInstanceOf(
                PrincipalRefusedException::class.java,
            ).extracting("reason")
            .isEqualTo(reason)
    }

    @Test
    fun `a key already fetched is not fetched again`() {
        val r = reader()
        repeat(3) { readBearer(r, token(claims())) }

        assertThat(identity.keysFetched.get()).isEqualTo(1)
    }

    @Test
    fun `an unknown key fetches the keys again at most once a minute`() {
        val r = reader()
        readBearer(r, token(claims()))
        val stranger = RSAKeyGenerator(2048).keyID("stranger").generate()
        val header =
            JWSHeader
                .Builder(JWSAlgorithm.RS256)
                .keyID("stranger")
                .type(JOSEObjectType.JWT)
                .build()
        val forged = JWSObject(header, Payload(claims())).apply { sign(RSASSASigner(stranger)) }.serialize()
        refusedAs("unknown-key") { readBearer(r, forged) }
        assertThat(identity.keysFetched.get()).isEqualTo(1)

        clock.now = clock.now.plusSeconds(60)
        refusedAs("unknown-key") { readBearer(r, forged) }

        assertThat(identity.keysFetched.get()).isEqualTo(2)
    }

    @Test
    fun `keys that cannot be read are no refusal`() {
        val t = token(claims())
        identity.keysStatus = 502

        assertThatThrownBy { readBearer(reader(), t) }.isInstanceOf(IdentityUnavailableException::class.java)
    }

    @Test
    fun `keys that are no JWKS are no refusal`() {
        val t = token(claims())
        identity.publish("<html>")

        assertThatThrownBy { readBearer(reader(), t) }.isInstanceOf(IdentityUnavailableException::class.java)
    }

    @Test
    fun `keys at no address are no refusal`() {
        val t = token(claims())
        val r =
            PrincipalReader(
                PrincipalReaderConfig(
                    "https://auth.example.test",
                    "web",
                    keysUri = URI.create("http://127.0.0.1:1/oidc/keys"),
                ),
            )

        assertThatThrownBy { readBearer(r, t) }.isInstanceOf(IdentityUnavailableException::class.java)
    }

    @Test
    fun `a token not signed with RS256 is refused`() {
        val header = JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build()
        val hmac = JWSObject(header, Payload(claims())).apply { sign(MACSigner(ByteArray(32))) }.serialize()

        refusedAs("bad-signature") { readBearer(reader(), hmac) }
    }

    @Test
    fun `a token that is no JWS is refused`() {
        refusedAs("bad-signature") { readBearer(reader(), "a.b.c") }
    }

    @Test
    fun `a signed payload that is no JSON is refused`() {
        identity.publish(JWKSet(key.toPublicJWK()).toString())
        val header =
            JWSHeader
                .Builder(JWSAlgorithm.RS256)
                .keyID("k")
                .type(JOSEObjectType.JWT)
                .build()
        val notJson = JWSObject(header, Payload("not json")).apply { sign(RSASSASigner(key)) }.serialize()

        refusedAs("bad-signature") { readBearer(reader(), notJson) }
    }

    @Test
    fun `an audience listing only the apps Client is read`() {
        assertThat(readBearer(reader(), token(claims { it["aud"] = listOf("web") })).subject).isEqualTo("ada")
    }

    @Test
    fun `an audience that is neither a string nor a list is refused`() {
        refusedAs("wrong-audience") { readBearer(reader(), token(claims { it["aud"] = 7 })) }
    }

    @Test
    fun `a kind v1 does not know is refused`() {
        refusedAs("unknown-major") { readBearer(reader(), token(claims { it["kind"] = "robot" })) }
    }

    @Test
    fun `a way in v1 does not know is refused`() {
        refusedAs("unknown-major") { readBearer(reader(), token(claims { it["via"] = "telepathy" })) }
    }

    @Test
    fun `a Principal stating no roles or subject holds empty ones`() {
        val p =
            readBearer(
                reader(),
                token(
                    claims {
                        it.remove("roles")
                        it.remove("sub")
                    },
                ),
            )

        assertThat(p.roles).isEmpty()
        assertThat(p.subject).isEmpty()
    }

    @Test
    fun `a revoked opaque token stops working within thirty seconds`() {
        val r = reader()
        identity.answer("web", "s3cret", "idapp_tool", JSONObjectUtils.toJSONString(claims { it["active"] = true }))
        readBearer(r, "idapp_tool")

        identity.answer("web", "s3cret", "idapp_tool", """{"active":false}""")
        clock.now = clock.now.plusSeconds(29)
        readBearer(r, "idapp_tool")
        clock.now = clock.now.plusSeconds(1)

        refusedAs("inactive") { readBearer(r, "idapp_tool") }
    }

    @Test
    fun `an opaque token is asked about once per thirty seconds`() {
        val r = reader()
        identity.answer("web", "s3cret", "idapp_tool", JSONObjectUtils.toJSONString(claims { it["active"] = true }))

        repeat(5) { readBearer(r, "idapp_tool") }

        assertThat(identity.asked.get()).isEqualTo(1)
    }

    @Test
    fun `an app without a Client secret refuses an opaque token without asking`() {
        refusedAs("inactive") { readBearer(reader(secret = null), "idpat_script") }
        assertThat(identity.asked.get()).isZero()
    }

    @Test
    fun `introspection that cannot be asked is no refusal and is asked again`() {
        val r = reader()
        identity.introspectStatus = 502
        assertThatThrownBy { readBearer(r, "idapp_tool") }.isInstanceOf(IdentityUnavailableException::class.java)

        identity.introspectStatus = 200
        identity.answer("web", "s3cret", "idapp_tool", JSONObjectUtils.toJSONString(claims { it["active"] = true }))

        assertThat(readBearer(r, "idapp_tool").subject).isEqualTo("ada")
    }

    @Test
    fun `an introspection answer that is no JSON is no refusal`() {
        identity.answer("web", "s3cret", "idapp_tool", "<html>")

        assertThatThrownBy { readBearer(reader(), "idapp_tool") }.isInstanceOf(IdentityUnavailableException::class.java)
    }

    @Test
    fun `a request without a bearer token or a signed header carries no Principal`() {
        val r = reader()
        for (authorization in listOf(null, "Basic dXNlcjpwYXNz", "Bearer ", "Bearer")) {
            refusedAs("missing") { r.read { if (it == "Authorization") authorization else null } }
        }
    }

    @Test
    fun `a refusal says why`() {
        assertThat(
            PrincipalRefusedException("expired").message,
        ).isEqualTo("the request's Principal is refused: expired")
    }
}
