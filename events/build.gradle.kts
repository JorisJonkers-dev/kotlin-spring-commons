plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
