import org.gradle.api.provider.ListProperty
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

// The default `UnsupportedSyncCheckpointStore` is a fail-loudly placeholder installed only when no
// real checkpoint store is provided. Its four override methods are behaviourally tested (they throw,
// asserted in AutoConfigCheckpointDefaultTest), but JaCoCo mis-attributes their coverage: Kotlin
// name-mangles the methods that take the `SyncName` value class (e.g. `loadCursor-JAdyWXs`) and the
// real calls dispatch through synthetic interface bridges. Feed the convention's exclusion list so
// both its report and verification ignore that nested object.
@Suppress("UNCHECKED_CAST")
(extensions.getByName("jacocoExclusionPatterns") as ListProperty<String>)
    .add("**/SyncAutoConfiguration\$UnsupportedSyncCheckpointStore*")

// Hold this module to full line coverage, above the shared 0.80 convention floor.
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
