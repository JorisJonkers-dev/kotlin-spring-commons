package com.jorisjonkers.personalstack.common.test.system

import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtensionContext

class FqcnShardCondition : ExecutionCondition {
    private val environment: SystemTestEnvironment

    constructor() : this(SystemTestEnvironment.current)

    constructor(environment: SystemTestEnvironment) {
        this.environment = environment
    }

    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
        val total = environment.shardCount ?: return ConditionEvaluationResult.enabled("sharding disabled")
        val index =
            environment.shardIndex
                ?: return ConditionEvaluationResult.disabled("test.shard.index unset (count=$total)")
        if (index !in 1..total) {
            return ConditionEvaluationResult.disabled("test.shard.index=$index outside 1..$total")
        }
        val fqcn =
            context.testClass.map { it.name }.orElse(null)
                ?: return ConditionEvaluationResult.enabled("no test class")
        return if (owns(fqcn, index, total)) {
            ConditionEvaluationResult.enabled("shard $index/$total owns $fqcn")
        } else {
            ConditionEvaluationResult.disabled("shard $index/$total does not own $fqcn")
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
