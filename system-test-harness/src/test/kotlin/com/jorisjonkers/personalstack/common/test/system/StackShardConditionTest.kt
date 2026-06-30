package com.jorisjonkers.personalstack.common.test.system

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtensionContext
import java.lang.reflect.Proxy
import java.util.Optional

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
        assertThat(shard?.index).isEqualTo(2)
        assertThat(shard?.count).isEqualTo(4)
    }

    @Test
    fun `loads shard configuration from default system properties`() =
        withShardProperties(index = "1", count = "3") {
            assertThat(StackTestShard.fromEnvironment()).isEqualTo(StackTestShard(index = 1, count = 3))
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
    fun `rejects invalid shard numbers`() {
        assertThatIllegalStateException()
            .isThrownBy {
                StackTestShard.fromEnvironment(
                    mapOf(
                        "test.shard.index" to "one",
                        "test.shard.count" to "2",
                    ),
                )
            }.withMessage("test.shard.index must be an integer, got 'one'")

        assertThatIllegalStateException()
            .isThrownBy {
                StackTestShard.fromEnvironment(
                    mapOf(
                        "test.shard.index" to "1",
                        "test.shard.count" to "many",
                    ),
                )
            }.withMessage("test.shard.count must be an integer, got 'many'")
    }

    @Test
    fun `rejects shard bounds outside configured range`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                StackTestShard.fromEnvironment(
                    mapOf(
                        "test.shard.index" to "1",
                        "test.shard.count" to "0",
                    ),
                )
            }.withMessage("test.shard.count must be greater than 0")

        assertThatIllegalArgumentException()
            .isThrownBy {
                StackTestShard.fromEnvironment(
                    mapOf(
                        "test.shard.index" to "3",
                        "test.shard.count" to "2",
                    ),
                )
            }.withMessage("test.shard.index must be between 1 and 2, got 3")
    }

    @Test
    fun `assigns a class to exactly one shard`() {
        val fqcn = "example.tests.LoginSystemTest"

        val owningShards = (1..4).filter { StackShardCondition.owns(fqcn, it, 4) }

        assertThat(owningShards).hasSize(1)
    }

    @Test
    fun `rejects invalid owns arguments`() {
        assertThatIllegalArgumentException()
            .isThrownBy { StackShardCondition.owns("Test", 1, 0) }
            .withMessage("shardCount must be positive")

        assertThatIllegalArgumentException()
            .isThrownBy { StackShardCondition.owns("Test", 0, 2) }
            .withMessage("shardIndex must be in 1..shardCount")
    }

    @Test
    fun `exposes JvmStatic companion functions`() {
        val shard =
            StackTestShard::class.java
                .getMethod("fromEnvironment", Map::class.java)
                .invoke(
                    null,
                    mapOf(
                        "test.shard.index" to "1",
                        "test.shard.count" to "2",
                    ),
                ) as StackTestShard
        val owns =
            StackShardCondition::class.java
                .getMethod("owns", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(null, "example.tests.LoginSystemTest", 1, 1) as Boolean

        assertThat(shard).isEqualTo(StackTestShard(index = 1, count = 2))
        assertThat(owns).isTrue()
    }

    @Test
    fun `enables execution when sharding is disabled`() =
        withShardProperties(index = null, count = null) {
            val result =
                StackShardCondition().evaluateExecutionCondition(
                    extensionContextFor(StackShardConditionTest::class.java),
                )

            assertThat(result.isDisabled).isFalse()
            assertThat(result.reason).hasValue("sharding disabled")
        }

    @Test
    fun `enables execution when context has no test class`() =
        withShardProperties(index = "1", count = "2") {
            val result = StackShardCondition().evaluateExecutionCondition(extensionContextFor(null))

            assertThat(result.isDisabled).isFalse()
            assertThat(result.reason).hasValue("no test class")
        }

    @Test
    fun `enables only the owning shard for execution context`() {
        val fqcn = StackShardConditionTest::class.java.name
        val shardCount = 4
        val owningShard = Math.floorMod(fqcn.hashCode(), shardCount) + 1
        val nonOwningShard = if (owningShard == shardCount) 1 else owningShard + 1

        withShardProperties(index = owningShard.toString(), count = shardCount.toString()) {
            val result =
                StackShardCondition().evaluateExecutionCondition(
                    extensionContextFor(StackShardConditionTest::class.java),
                )

            assertThat(result.isDisabled).isFalse()
            assertThat(result.reason).hasValue("shard $owningShard/$shardCount owns $fqcn")
        }
        withShardProperties(index = nonOwningShard.toString(), count = shardCount.toString()) {
            val result =
                StackShardCondition().evaluateExecutionCondition(
                    extensionContextFor(StackShardConditionTest::class.java),
                )

            assertThat(result.isDisabled).isTrue()
            assertThat(result.reason).hasValue("shard $nonOwningShard/$shardCount does not own $fqcn")
        }
    }

    private fun extensionContextFor(testClass: Class<*>?): ExtensionContext =
        Proxy.newProxyInstance(
            ExtensionContext::class.java.classLoader,
            arrayOf(ExtensionContext::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getTestClass" -> Optional.ofNullable(testClass)
                "toString" -> "ExtensionContext(testClass=$testClass)"
                else -> error("Unexpected ExtensionContext method: ${method.name}")
            }
        } as ExtensionContext

    private fun withShardProperties(
        index: String?,
        count: String?,
        block: () -> Unit,
    ) {
        val previousIndex = System.getProperty("test.shard.index")
        val previousCount = System.getProperty("test.shard.count")
        try {
            setOrClear("test.shard.index", index)
            setOrClear("test.shard.count", count)
            block()
        } finally {
            setOrClear("test.shard.index", previousIndex)
            setOrClear("test.shard.count", previousCount)
        }
    }

    private fun setOrClear(
        name: String,
        value: String?,
    ) {
        if (value == null) {
            System.clearProperty(name)
        } else {
            System.setProperty(name, value)
        }
    }
}
