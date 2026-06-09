package com.jorisjonkers.personalstack.common.vault

import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer
import java.util.Collections

fun interface JwtClaimCustomizer {
    fun customize(context: JwtClaimCustomizationContext)
}

data class JwtClaimCustomizationContext(
    val principalName: String?,
    val clientId: String?,
    val tokenType: String,
    val authorizedScopes: Set<String>,
    val claims: MutableMap<String, Any?>,
)

class CompositeJwtClaimCustomizer(
    private val customizers: List<JwtClaimCustomizer>,
) : OAuth2TokenCustomizer<JwtEncodingContext> {
    override fun customize(context: JwtEncodingContext) {
        if (customizers.isEmpty()) {
            return
        }
        val claims = LinkedHashMap<String, Any?>(context.claims.build().claims)
        val customizationContext =
            JwtClaimCustomizationContext(
                principalName = context.getPrincipal<Authentication>()?.name,
                clientId = context.registeredClient?.clientId,
                tokenType = context.tokenType.value,
                authorizedScopes = context.authorizedScopes ?: emptySet(),
                claims = claims,
            )
        customizers.forEach { it.customize(customizationContext) }
        claims.filterValues { it != null }.forEach { (name, value) -> context.claims.claim(name, value) }
    }

    companion object {
        @JvmStatic
        fun empty(): CompositeJwtClaimCustomizer = CompositeJwtClaimCustomizer(Collections.emptyList())
    }
}
