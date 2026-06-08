package com.jorisjonkers.personalstack.common.timing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.core.annotation.Order
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Instant

class SecurityChainBoundaryFilterTest {
    private val filter = SecurityChainBoundaryFilter()

    @Test
    fun `sets SECURITY_CHAIN_END_INSTANT on the request and continues the chain`() {
        val request = MockHttpServletRequest("GET", "/api/v1/auth/me")
        val response = MockHttpServletResponse()
        val before = Instant.now()

        filter.doFilter(request, response, MockFilterChain())

        val recorded =
            request.getAttribute(RequestTimingAttributes.SECURITY_CHAIN_END_INSTANT) as? Instant
        assertThat(recorded).isNotNull()
        assertThat(recorded).isAfterOrEqualTo(before)
    }

    @Test
    fun `is ordered immediately after Spring Security's FilterChainProxy`() {
        // SecurityProperties.DEFAULT_FILTER_ORDER is -100; this filter
        // must run at -99 so it sits inside the security chain on the
        // way in and unwinds before security on the way out.
        val order = AnnotationUtils.findAnnotation(filter::class.java, Order::class.java)
        assertThat(order).isNotNull()
        assertThat(order!!.value).isEqualTo(-99)
    }
}
