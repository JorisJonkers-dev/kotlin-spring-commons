import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
}

dependencies {
    api(libs.junit.jupiter)
    api(libs.assertj.core)
    api(libs.mockk)
    api(libs.jakarta.servlet.api)
    api(libs.jakarta.mail.angus)
    api(libs.playwright)
    api(libs.rest.assured)
    api(libs.spring.boot.autoconfigure)
    api(libs.spring.boot.test)
    api(libs.spring.boot.test.autoconfigure)
    api(libs.spring.boot.webmvc.test)
    api(libs.spring.test)
    api(libs.springdoc.openapi.starter.webmvc.api)

    testRuntimeOnly(libs.junit.platform.launcher)
}

// These system-test fixtures require real external infrastructure to execute:
// PlaywrightSystemTestBase launches a browser, and StalwartMailClient opens an
// IMAP connection. Keep pure helper classes covered by direct unit tests.
val environmentBoundCoverageExcludes =
    listOf(
        "**/PlaywrightSystemTestBase*",
        "**/StalwartMailClient*",
    )

tasks.named<JacocoReport>("jacocoTestReport") {
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) {
                    exclude(environmentBoundCoverageExcludes)
                }
            },
        ),
    )
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) {
                    exclude(environmentBoundCoverageExcludes)
                }
            },
        ),
    )
}
