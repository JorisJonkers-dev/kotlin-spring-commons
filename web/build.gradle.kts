import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":exceptions"))

    implementation(libs.jackson.module.kotlin)
    compileOnly(libs.spring.boot.starter.web)
    compileOnly(libs.spring.boot.starter.validation)
    compileOnly(libs.spring.boot.autoconfigure)
    // spring-security-web is integral to this module's filters (CsrfTokenController,
    // PublicAuthRateLimitFilter's IpAddressMatcher), so it is exposed transitively.
    // compileOnly here regressed consumers without their own spring-security-web
    // (NoClassDefFoundError org.springframework.security.web.csrf.CsrfToken during
    // WebUtilitiesAutoConfiguration introspection).
    api(libs.spring.security.web)
    compileOnly(libs.springdoc.openapi.starter.webmvc.api)
    compileOnly(libs.spring.tx)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.assertj.core)
    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.starter.validation)
    testImplementation(libs.spring.boot.autoconfigure)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.security.web)
    testImplementation(libs.springdoc.openapi.starter.webmvc.api)
    testImplementation(libs.spring.test)
    testImplementation(libs.spring.tx)
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
