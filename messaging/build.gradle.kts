import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":events"))

    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.tools.jackson.databind)

    compileOnly(libs.spring.boot.autoconfigure)
    compileOnly(libs.spring.boot.starter.amqp)
    compileOnly(libs.spring.context)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.assertj.core)
    testImplementation(libs.spring.boot.autoconfigure)
    testImplementation(libs.spring.boot.starter.amqp)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.test)
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
