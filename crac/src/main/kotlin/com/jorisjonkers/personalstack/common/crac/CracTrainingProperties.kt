package com.jorisjonkers.personalstack.common.crac

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "crac.train")
data class CracTrainingProperties(
    val enabled: Boolean = false,
    val endpoints: List<String> = emptyList(),
    val iterations: Int = 200,
    val checkpointAfterWarmup: Boolean = true,
)
