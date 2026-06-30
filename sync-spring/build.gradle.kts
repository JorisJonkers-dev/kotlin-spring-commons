import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":sync-core"))

    compileOnly(libs.spring.boot.autoconfigure)
    compileOnly(libs.spring.context)
    compileOnly(libs.spring.tx)
    compileOnly(libs.resilience4j.circuitbreaker)
    compileOnly(libs.resilience4j.retry)
    compileOnly(libs.micrometer.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.assertj.core)
    testImplementation(libs.spring.boot.autoconfigure)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.context)
    testImplementation(libs.spring.tx)
    testImplementation(libs.resilience4j.circuitbreaker)
    testImplementation(libs.resilience4j.retry)
    testImplementation(libs.micrometer.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// This module is held to full line coverage (overrides the repo-wide 0.80 floor).
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
