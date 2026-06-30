import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

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

// The default `UnsupportedSyncCheckpointStore` is a fail-loudly placeholder installed only when no
// real checkpoint store is provided. Its four override methods are behaviourally tested (they throw,
// asserted in AutoConfigCheckpointDefaultTest), but JaCoCo mis-attributes their coverage: Kotlin
// name-mangles the methods that take the `SyncName` value class (e.g. `loadCursor-JAdyWXs`) and the
// real calls dispatch through synthetic interface bridges, so the mangled methods read as uncovered
// even though `unsupported()` is exercised. Exclude only that nested object from coverage.
val coverageExcludes = listOf("**/SyncAutoConfiguration\$UnsupportedSyncCheckpointStore*")

tasks.named<JacocoReport>("jacocoTestReport") {
    classDirectories.setFrom(
        files(classDirectories.files.map { fileTree(it) { exclude(coverageExcludes) } }),
    )
}

// This module is held to full line coverage (overrides the repo-wide 0.80 floor).
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    classDirectories.setFrom(
        files(classDirectories.files.map { fileTree(it) { exclude(coverageExcludes) } }),
    )
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
