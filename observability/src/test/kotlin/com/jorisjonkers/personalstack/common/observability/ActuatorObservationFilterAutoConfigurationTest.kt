package com.jorisjonkers.personalstack.common.observability

import io.micrometer.observation.Observation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.server.observation.ServerRequestObservationContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class ActuatorObservationFilterAutoConfigurationTest {
    private val predicate = ActuatorObservationPredicate

    @Test
    fun `passes through non http server observations untouched`() {
        assertThat(predicate.test("jdbc.query", Observation.Context())).isTrue()
    }

    @Test
    fun `passes through requests without a server context`() {
        assertThat(predicate.test("http.server.requests", Observation.Context())).isTrue()
    }

    @Test
    fun `filters spring actuator endpoints`() {
        assertThat(predicate.testForUri("/actuator/health")).isFalse()
        assertThat(predicate.testForUri("/actuator/health/liveness")).isFalse()
        assertThat(predicate.testForUri("/actuator/prometheus")).isFalse()
    }

    @Test
    fun `filters api-prefixed actuator and v1 health alias`() {
        assertThat(predicate.testForUri("/api/actuator/health")).isFalse()
        assertThat(predicate.testForUri("/api/v1/health")).isFalse()
    }

    @Test
    fun `keeps real api endpoints`() {
        assertThat(predicate.testForUri("/api/v1/users/me")).isTrue()
        assertThat(predicate.testForUri("/api/v1/auth/login")).isTrue()
        assertThat(predicate.testForUri("/")).isTrue()
    }

    private fun ActuatorObservationPredicate.testForUri(uri: String): Boolean {
        val request =
            MockHttpServletRequest().apply {
                method = "GET"
                requestURI = uri
            }
        val context = ServerRequestObservationContext(request, MockHttpServletResponse())
        return test("http.server.requests", context)
    }
}
