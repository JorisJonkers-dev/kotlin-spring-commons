import org.gradle.api.provider.ListProperty

plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
}

dependencies {
    api(libs.junit.jupiter)
    api(libs.playwright)
    api(libs.testcontainers)
    api(libs.testcontainers.junit.jupiter)

    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Environment-bound fixtures require real infrastructure (a browser) to execute, so they are
// excluded from coverage through the shared testing convention's exclusion list.
@Suppress("UNCHECKED_CAST")
(extensions.getByName("jacocoExclusionPatterns") as ListProperty<String>).addAll(
    "**/PlaywrightStackTestBase*",
)
