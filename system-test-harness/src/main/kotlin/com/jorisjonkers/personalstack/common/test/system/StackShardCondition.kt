package com.jorisjonkers.personalstack.common.test.system

import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtensionContext

class StackShardCondition : ExecutionCondition {
    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
        val shard = StackTestShard.fromEnvironment()
        val testClass = context.testClass.orElse(null)
        return when {
            shard == null -> ConditionEvaluationResult.enabled("sharding disabled")
            testClass == null -> ConditionEvaluationResult.enabled("no test class")
            owns(testClass.name, shard.index, shard.count) ->
                ConditionEvaluationResult.enabled("shard ${shard.index}/${shard.count} owns ${testClass.name}")
            else ->
                ConditionEvaluationResult.disabled("shard ${shard.index}/${shard.count} does not own ${testClass.name}")
        }
    }

    companion object {
        @JvmStatic
        fun owns(
            fqcn: String,
            shardIndex: Int,
            shardCount: Int,
        ): Boolean {
            require(shardCount > 0) { "shardCount must be positive" }
            require(shardIndex in 1..shardCount) { "shardIndex must be in 1..shardCount" }
            return Math.floorMod(fqcn.hashCode(), shardCount) == shardIndex - 1
        }
    }
}

data class StackTestShard(
    val index: Int,
    val count: Int,
) {
    companion object {
        @JvmStatic
        fun fromEnvironment(properties: Map<String, String> = systemProperties()): StackTestShard? {
            val indexValue = properties["test.shard.index"]?.trim()
            val countValue = properties["test.shard.count"]?.trim()

            if (indexValue.isNullOrEmpty() && countValue.isNullOrEmpty()) {
                return null
            }

            require(!indexValue.isNullOrEmpty() && !countValue.isNullOrEmpty()) {
                "Both test.shard.index and test.shard.count must be set together"
            }

            val index = indexValue.toIntOrNull() ?: error("test.shard.index must be an integer, got '$indexValue'")
            val count = countValue.toIntOrNull() ?: error("test.shard.count must be an integer, got '$countValue'")

            require(count > 0) { "test.shard.count must be greater than 0" }
            require(index in 1..count) { "test.shard.index must be between 1 and $count, got $index" }

            return StackTestShard(index = index, count = count)
        }

        private fun systemProperties(): Map<String, String> =
            System.getProperties().stringPropertyNames().associateWith(System::getProperty)
    }
}
