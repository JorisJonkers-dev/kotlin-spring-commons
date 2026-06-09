package com.jorisjonkers.personalstack.common.test.system

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtensionContext
import java.util.Optional

class FqcnShardConditionTest {
    @Test
    fun `assigns each fqcn to exactly one shard`() {
        val fqcn = "com.example.systemtests.AccountFlowTest"
        val owners = (1..5).filter { FqcnShardCondition.owns(fqcn, it, 5) }

        assertThat(owners).hasSize(1)
    }

    @Test
    fun `rejects invalid shard configuration`() {
        assertThatThrownBy { FqcnShardCondition.owns("Test", 0, 2) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { FqcnShardCondition.owns("Test", 1, 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `enables tests when sharding is disabled`() {
        val condition =
            FqcnShardCondition(
                SystemTestEnvironment(
                    mapOf("test.shard.count" to "1"),
                ),
            )

        val result = condition.evaluateExecutionCondition(extensionContextFor(FqcnShardConditionTest::class.java))

        assertThat(result.isDisabled).isFalse()
        assertThat(result.reason).contains("sharding disabled")
    }

    @Test
    fun `disables tests when shard index is missing`() {
        val condition =
            FqcnShardCondition(
                SystemTestEnvironment(
                    mapOf("test.shard.count" to "3"),
                ),
            )

        val result = condition.evaluateExecutionCondition(extensionContextFor(FqcnShardConditionTest::class.java))

        assertThat(result.isDisabled).isTrue()
        assertThat(result.reason).contains("test.shard.index unset (count=3)")
    }

    @Test
    fun `disables tests when shard index is outside configured range`() {
        val condition =
            FqcnShardCondition(
                SystemTestEnvironment(
                    mapOf(
                        "test.shard.index" to "4",
                        "test.shard.count" to "3",
                    ),
                ),
            )

        val result = condition.evaluateExecutionCondition(extensionContextFor(FqcnShardConditionTest::class.java))

        assertThat(result.isDisabled).isTrue()
        assertThat(result.reason).contains("test.shard.index=4 outside 1..3")
    }

    @Test
    fun `enables contexts without a test class`() {
        val condition =
            FqcnShardCondition(
                SystemTestEnvironment(
                    mapOf(
                        "test.shard.index" to "1",
                        "test.shard.count" to "2",
                    ),
                ),
            )

        val result = condition.evaluateExecutionCondition(extensionContextFor(null))

        assertThat(result.isDisabled).isFalse()
        assertThat(result.reason).contains("no test class")
    }

    @Test
    fun `enables only the owning shard for the fqcn`() {
        val fqcn = FqcnShardConditionTest::class.java.name
        val shardCount = 4
        val owningShard = Math.floorMod(fqcn.hashCode(), shardCount) + 1
        val nonOwningShard = if (owningShard == shardCount) 1 else owningShard + 1

        val owningResult =
            FqcnShardCondition(
                SystemTestEnvironment(
                    mapOf(
                        "test.shard.index" to owningShard.toString(),
                        "test.shard.count" to shardCount.toString(),
                    ),
                ),
            ).evaluateExecutionCondition(extensionContextFor(FqcnShardConditionTest::class.java))
        val nonOwningResult =
            FqcnShardCondition(
                SystemTestEnvironment(
                    mapOf(
                        "test.shard.index" to nonOwningShard.toString(),
                        "test.shard.count" to shardCount.toString(),
                    ),
                ),
            ).evaluateExecutionCondition(extensionContextFor(FqcnShardConditionTest::class.java))

        assertThat(owningResult.isDisabled).isFalse()
        assertThat(owningResult.reason).hasValueSatisfying { reason ->
            assertThat(reason).contains("owns $fqcn")
        }
        assertThat(nonOwningResult.isDisabled).isTrue()
        assertThat(nonOwningResult.reason).hasValueSatisfying { reason ->
            assertThat(reason).contains("does not own $fqcn")
        }
    }

    private fun extensionContextFor(testClass: Class<*>?): ExtensionContext =
        mockk {
            every { getTestClass() } returns Optional.ofNullable(testClass)
        }
}
