import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    `java-library`
    `maven-publish`
}

dependencies {
    api(libs.spring.security.oauth2.jose)

    compileOnly(libs.spring.boot.starter.web)
    compileOnly(libs.spring.boot.autoconfigure)
    compileOnly(libs.spring.security.web)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.assertj.core)
    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.autoconfigure)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.security.web)
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
