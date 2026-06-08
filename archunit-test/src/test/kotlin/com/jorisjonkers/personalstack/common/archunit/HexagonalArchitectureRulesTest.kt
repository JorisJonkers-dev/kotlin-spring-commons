package com.jorisjonkers.personalstack.common.archunit

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HexagonalArchitectureRulesTest {
    @Test
    fun `all individual rules are defined`() {
        assertThat(HexagonalArchitectureRules.NO_CIRCULAR_DEPENDENCIES).isNotNull
        assertThat(HexagonalArchitectureRules.LAYERED_ARCHITECTURE).isNotNull
        assertThat(HexagonalArchitectureRules.DOMAIN_MUST_NOT_DEPEND_ON_SPRING).isNotNull
        assertThat(HexagonalArchitectureRules.CONTROLLERS_MUST_NOT_ACCESS_REPOSITORIES).isNotNull
        assertThat(HexagonalArchitectureRules.NO_FIELD_INJECTION).isNotNull
        assertThat(HexagonalArchitectureRules.NAMING_CONTROLLERS).isNotNull
        assertThat(HexagonalArchitectureRules.NAMING_REPOSITORIES).isNotNull
        assertThat(HexagonalArchitectureRules.COMMANDS_MUST_NOT_DEPEND_ON_WEB_OR_INFRA).isNotNull
        assertThat(HexagonalArchitectureRules.DOMAIN_MUST_NOT_DEPEND_ON_INFRASTRUCTURE).isNotNull
    }

    @Test
    fun `ALL_RULES contains expected number of rules`() {
        assertThat(HexagonalArchitectureRules.ALL_RULES).hasSize(7)
    }

    @Test
    fun `ALL_RULES contains the domain spring rule`() {
        assertThat(HexagonalArchitectureRules.ALL_RULES)
            .contains(HexagonalArchitectureRules.DOMAIN_MUST_NOT_DEPEND_ON_SPRING)
    }

    @Test
    fun `ALL_RULES contains the controller repository rule`() {
        assertThat(HexagonalArchitectureRules.ALL_RULES)
            .contains(HexagonalArchitectureRules.CONTROLLERS_MUST_NOT_ACCESS_REPOSITORIES)
    }

    @Test
    fun `ALL_RULES contains the no field injection rule`() {
        assertThat(HexagonalArchitectureRules.ALL_RULES)
            .contains(HexagonalArchitectureRules.NO_FIELD_INJECTION)
    }

    @Test
    fun `ALL_RULES contains naming rules`() {
        assertThat(HexagonalArchitectureRules.ALL_RULES)
            .contains(HexagonalArchitectureRules.NAMING_CONTROLLERS)
            .contains(HexagonalArchitectureRules.NAMING_REPOSITORIES)
    }

    @Test
    fun `ALL_RULES contains command isolation rule`() {
        assertThat(HexagonalArchitectureRules.ALL_RULES)
            .contains(HexagonalArchitectureRules.COMMANDS_MUST_NOT_DEPEND_ON_WEB_OR_INFRA)
    }

    @Test
    fun `ALL_RULES contains domain infrastructure rule`() {
        assertThat(HexagonalArchitectureRules.ALL_RULES)
            .contains(HexagonalArchitectureRules.DOMAIN_MUST_NOT_DEPEND_ON_INFRASTRUCTURE)
    }

    @Test
    fun `NO_CIRCULAR_DEPENDENCIES and LAYERED_ARCHITECTURE are not in ALL_RULES`() {
        assertThat(HexagonalArchitectureRules.ALL_RULES)
            .doesNotContain(HexagonalArchitectureRules.NO_CIRCULAR_DEPENDENCIES)
            .doesNotContain(HexagonalArchitectureRules.LAYERED_ARCHITECTURE)
    }

    @Test
    fun `rules have descriptions containing ADR references`() {
        val description = HexagonalArchitectureRules.DOMAIN_MUST_NOT_DEPEND_ON_SPRING.description
        assertThat(description).contains("ADR")
    }
}
