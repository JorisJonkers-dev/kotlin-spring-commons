package com.jorisjonkers.personalstack.common.web

import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class CsrfTokenController(
    private val properties: WebUtilitiesProperties.CsrfProperties = WebUtilitiesProperties.CsrfProperties(),
) {
    @GetMapping("\${extratoast.web.csrf.path:/csrf}")
    fun csrf(csrfToken: CsrfToken): Map<String, String> = mapOf(properties.tokenField to csrfToken.token)
}
