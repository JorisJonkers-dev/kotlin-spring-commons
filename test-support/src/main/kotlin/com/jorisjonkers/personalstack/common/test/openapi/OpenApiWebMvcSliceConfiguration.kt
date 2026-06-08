package com.jorisjonkers.personalstack.common.test.openapi

import org.springdoc.core.configuration.SpringDocConfiguration
import org.springdoc.core.configuration.SpringDocJacksonKotlinModuleConfiguration
import org.springdoc.core.configuration.SpringDocKotlinConfiguration
import org.springdoc.core.configuration.SpringDocPageableConfiguration
import org.springdoc.core.configuration.SpringDocSecurityConfiguration
import org.springdoc.core.configuration.SpringDocSortConfiguration
import org.springdoc.core.configuration.SpringDocSpecPropertiesConfiguration
import org.springdoc.core.properties.SpringDocConfigProperties
import org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@ImportAutoConfiguration(
    SpringDocConfiguration::class,
    SpringDocConfigProperties::class,
    SpringDocSpecPropertiesConfiguration::class,
    SpringDocKotlinConfiguration::class,
    SpringDocJacksonKotlinModuleConfiguration::class,
    SpringDocPageableConfiguration::class,
    SpringDocSortConfiguration::class,
    SpringDocSecurityConfiguration::class,
    SpringDocWebMvcConfiguration::class,
    MultipleOpenApiSupportConfiguration::class,
)
class OpenApiWebMvcSliceConfiguration
