package com.jorisjonkers.personalstack.common.crac

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@ConditionalOnProperty(prefix = "crac.train", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(CracTrainingProperties::class)
class CracAutoConfiguration {
    @Bean
    fun cracTrainingRunner(properties: CracTrainingProperties): CracTrainingRunner = CracTrainingRunner(properties)
}
