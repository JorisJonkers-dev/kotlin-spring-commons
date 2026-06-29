package com.jorisjonkers.personalstack.common.test.system

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class StackShardConditionTest {
    @Test
    fun `loads shard configuration from properties`() {
        val shard =
            StackTestShard.fromEnvironment(
                mapOf(
                    "test.shard.index" to "2",
                    "test.shard.count" to "4",
                ),
            )

        assertThat(shard).isEqualTo(StackTestShard(index = 2, count = 4))
    }

    @Test
    fun `returns null when sharding is disabled`() {
        assertThat(StackTestShard.fromEnvironment(emptyMap())).isNull()
    }

    @Test
    fun `rejects incomplete shard configuration`() {
        assertThatIllegalArgumentException()
            .isThrownBy { StackTestShard.fromEnvironment(mapOf("test.shard.index" to "1")) }
    }

    @Test
    fun `assigns a class to exactly one shard`() {
        val fqcn = "example.tests.LoginSystemTest"

        val owningShards = (1..4).filter { StackShardCondition.owns(fqcn, it, 4) }

        assertThat(owningShards).hasSize(1)
    }
}
