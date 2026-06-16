package com.jorisjonkers.personalstack.common.identity

import java.util.UUID

data class ForwardAuthPrincipal(
    val userId: UUID,
    val roles: Set<String>,
    val username: String?,
    val credentialSource: CredentialSource,
)

enum class CredentialSource {
    EDGE_ASSERTION,
    AUTHORIZATION_BEARER,
}
