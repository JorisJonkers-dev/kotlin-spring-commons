plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
}

dependencies {
    api(libs.junit.jupiter)
    api(libs.assertj.core)
    api(libs.mockk)
    api(libs.spring.boot.test)
    api(libs.spring.test)

    testRuntimeOnly(libs.junit.platform.launcher)
}
