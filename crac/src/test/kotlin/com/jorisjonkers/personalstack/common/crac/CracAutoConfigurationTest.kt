package com.jorisjonkers.personalstack.common.crac

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CracAutoConfigurationTest {
    @Test
    fun `creates training runner bean with configured properties`() {
        val properties =
            CracTrainingProperties(
                enabled = true,
                endpoints = listOf("/ready"),
                iterations = 1,
                checkpointAfterWarmup = false,
            )

        val runner = CracAutoConfiguration().cracTrainingRunner(properties)

        assertThat(runner).isInstanceOf(CracTrainingRunner::class.java)
    }
}
