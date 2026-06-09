package com.jorisjonkers.personalstack.common.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.web.csrf.DefaultCsrfToken

class CsrfTokenControllerTest {
    @Test
    fun `returns csrf token under configurable field name`() {
        val controller =
            CsrfTokenController(
                WebUtilitiesProperties.CsrfProperties(tokenField = "csrfToken"),
            )

        val body = controller.csrf(DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "raw-token"))

        assertThat(body).containsEntry("csrfToken", "raw-token")
    }
}
