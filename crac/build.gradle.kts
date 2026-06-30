import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    `java-library`
    `maven-publish`
}

dependencies {
    compileOnly(libs.crac)
    compileOnly(libs.spring.boot.autoconfigure)
    compileOnly(libs.spring.boot.starter.web)
    compileOnly(libs.spring.context)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.crac)
    testImplementation(libs.spring.boot.starter.web)
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
