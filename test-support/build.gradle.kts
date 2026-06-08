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
    api(libs.spring.boot.autoconfigure)
    api(libs.spring.boot.test)
    api(libs.spring.boot.test.autoconfigure)
    api(libs.spring.boot.webmvc.test)
    api(libs.spring.test)
    api(libs.springdoc.openapi.starter.webmvc.api)

    testRuntimeOnly(libs.junit.platform.launcher)
}
