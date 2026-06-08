package com.jorisjonkers.personalstack.common.vault

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Component
import org.springframework.vault.core.VaultTemplate
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

data class VaultTransitKeyVersion(
    val version: Int,
    val keyId: String,
    val publicKey: RSAPublicKey,
)

interface VaultTransitClient {
    fun readKeyVersions(keyName: String): List<VaultTransitKeyVersion>

    fun sign(
        keyName: String,
        input: ByteArray,
        keyVersion: Int,
    ): ByteArray
}

@Component
@ConditionalOnProperty("auth.transit.enabled", havingValue = "true")
open class SpringVaultTransitClient(
    private val vaultTemplate: VaultTemplate,
) : VaultTransitClient {
    companion object {
        private const val TRANSIT_SIGNATURE_PARTS = 3
    }

    override fun readKeyVersions(keyName: String): List<VaultTransitKeyVersion> {
        val response =
            vaultTemplate.read("transit/keys/$keyName")
                ?: error("No transit key found at transit/keys/$keyName")
        val rawKeys =
            response.data?.get("keys") as? Map<*, *>
                ?: error("Transit key '$keyName' does not expose key versions")

        return rawKeys.entries
            .mapNotNull { (version, rawValue) ->
                val publicKeyPem = (rawValue as? Map<*, *>)?.get("public_key")?.toString() ?: return@mapNotNull null
                val keyVersion = version.toString().toInt()
                VaultTransitKeyVersion(
                    version = keyVersion,
                    keyId = "$keyName-v$keyVersion",
                    publicKey = parsePublicKey(publicKeyPem),
                )
            }.sortedBy { it.version }
            .ifEmpty { error("Transit key '$keyName' does not expose RSA public keys") }
    }

    override fun sign(
        keyName: String,
        input: ByteArray,
        keyVersion: Int,
    ): ByteArray {
        val payload =
            mapOf(
                "input" to Base64.getEncoder().encodeToString(input),
                "key_version" to keyVersion,
                "hash_algorithm" to "sha2-256",
                "signature_algorithm" to "pkcs1v15",
                "prehashed" to false,
            )

        val response =
            vaultTemplate.write("transit/sign/$keyName", payload)
                ?: error("Transit signing request returned no response for key '$keyName'")
        val rawSignature =
            response.data?.get("signature")?.toString()
                ?: error("Transit signing response did not include a signature for key '$keyName'")
        val signatureParts = rawSignature.split(":")
        require(signatureParts.size == TRANSIT_SIGNATURE_PARTS) {
            "Unexpected transit signature format for key '$keyName'"
        }
        return Base64.getDecoder().decode(signatureParts[2])
    }

    private fun parsePublicKey(pem: String): RSAPublicKey {
        val base64 =
            pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\\s".toRegex(), "")
        val keyBytes = Base64.getDecoder().decode(base64)
        val keySpec = X509EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("RSA").generatePublic(keySpec) as RSAPublicKey
    }
}

class VaultTransitJwtEncoder(
    private val transitClient: VaultTransitClient,
    private val keyName: String,
    private val activeKey: VaultTransitKeyVersion,
) : JwtEncoder {
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    override fun encode(parameters: JwtEncoderParameters): Jwt {
        val claims = parameters.claims
        val claimsMap = LinkedHashMap(claims.claims)
        val headerMap =
            linkedMapOf<String, Any>(
                "alg" to "RS256",
                "typ" to "JWT",
                "kid" to activeKey.keyId,
            )

        val signingInput =
            listOf(
                headerMap.toBase64UrlJson(),
                claimsMap.toBase64UrlJson(),
            ).joinToString(".")

        val signature =
            transitClient.sign(
                keyName = keyName,
                input = signingInput.toByteArray(StandardCharsets.US_ASCII),
                keyVersion = activeKey.version,
            )

        val tokenValue = "$signingInput.${signature.toBase64Url()}"
        return Jwt(
            tokenValue,
            claims.issuedAt ?: Instant.now(),
            claims.expiresAt ?: claims.issuedAt ?: Instant.now(),
            headerMap,
            claimsMap,
        )
    }

    private fun Map<String, Any>.toBase64UrlJson(): String = objectMapper.writeValueAsBytes(this).toBase64Url()

    private fun ByteArray.toBase64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)
}
