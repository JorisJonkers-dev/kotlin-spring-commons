import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    `java-library`
    `maven-publish`
}

dependencies {
    compileOnly(libs.spring.boot.autoconfigure)
    compileOnly(libs.spring.boot.starter.web)
    compileOnly(libs.spring.context)
    compileOnly(libs.spring.aop)
    compileOnly(libs.aspectjweaver)
    compileOnly(libs.opentelemetry.api)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.spring.aop)
    testImplementation(libs.aspectjweaver)
    testImplementation(libs.spring.test)
    testImplementation(libs.opentelemetry.api)
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)
}

afterEvaluate {
    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = "1.00".toBigDecimal()
                }
            }
        }
    }
}
