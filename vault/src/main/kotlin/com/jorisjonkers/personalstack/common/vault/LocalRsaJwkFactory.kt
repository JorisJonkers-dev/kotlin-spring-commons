package com.jorisjonkers.personalstack.common.vault

import com.nimbusds.jose.jwk.RSAKey
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

private const val RSA_KEY_SIZE_BITS = 2048

object LocalRsaJwkFactory {
    @JvmStatic
    fun generate(keyId: String = "local-dev"): RSAKey {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(RSA_KEY_SIZE_BITS)
        val pair = generator.generateKeyPair()
        return RSAKey
            .Builder(pair.public as RSAPublicKey)
            .privateKey(pair.private as RSAPrivateKey)
            .keyID(keyId)
            .build()
    }
}
