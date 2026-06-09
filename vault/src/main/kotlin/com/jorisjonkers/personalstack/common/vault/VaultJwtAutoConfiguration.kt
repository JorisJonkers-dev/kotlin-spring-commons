package com.jorisjonkers.personalstack.common.vault

import com.nimbusds.jose.jwk.JWKSelector
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.vault.core.VaultTemplate

@AutoConfiguration
@ConditionalOnClass(JwtEncoder::class)
@EnableConfigurationProperties(VaultJwtProperties::class)
open class VaultJwtAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(VaultTransitClient::class)
    @ConditionalOnBean(VaultTemplate::class)
    @ConditionalOnProperty("auth.transit.enabled", havingValue = "true")
    open fun springVaultTransitClient(vaultTemplate: VaultTemplate): VaultTransitClient =
        SpringVaultTransitClient(vaultTemplate)

    @Bean
    @ConditionalOnMissingBean(VaultTransitJwkSource::class)
    @ConditionalOnProperty("auth.transit.enabled", havingValue = "true")
    open fun vaultTransitJwkSource(
        transitClient: VaultTransitClient,
        properties: VaultJwtProperties,
    ): VaultTransitJwkSource =
        VaultTransitJwkSource(
            transitClient = transitClient,
            keyName = properties.keyName,
            refreshInterval = properties.jwksRefresh,
        ).also { it.refreshIfStale() }

    @Bean
    @ConditionalOnMissingBean(JwtEncoder::class)
    @ConditionalOnProperty("auth.transit.enabled", havingValue = "true")
    open fun vaultTransitJwtEncoder(
        transitClient: VaultTransitClient,
        properties: VaultJwtProperties,
    ): JwtEncoder =
        VaultTransitJwtEncoder(transitClient, properties.keyName) {
            transitClient
                .readKeyVersions(properties.keyName)
                .maxByOrNull { it.version }
                ?: error("Transit key '${properties.keyName}' does not expose RSA public keys")
        }

    @Bean
    @ConditionalOnMissingBean(RSAKey::class)
    @ConditionalOnProperty("auth.transit.enabled", havingValue = "false", matchIfMissing = true)
    open fun localRsaJwk(properties: VaultJwtProperties): RSAKey =
        LocalRsaJwkFactory.generate(properties.localKeyId)

    @Bean
    @ConditionalOnMissingBean(JWKSource::class)
    @ConditionalOnProperty("auth.transit.enabled", havingValue = "false", matchIfMissing = true)
    open fun localJwkSource(rsaKey: RSAKey): JWKSource<SecurityContext> {
        val jwkSet = JWKSet(rsaKey)
        return JWKSource { selector: JWKSelector, _: SecurityContext? -> selector.select(jwkSet) }
    }

    @Bean
    @ConditionalOnMissingBean(JwtEncoder::class)
    @ConditionalOnProperty("auth.transit.enabled", havingValue = "false", matchIfMissing = true)
    open fun localJwtEncoder(jwkSource: JWKSource<SecurityContext>): JwtEncoder =
        NimbusJwtEncoder(jwkSource)
}
