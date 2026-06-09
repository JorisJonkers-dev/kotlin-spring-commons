plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    `java-library`
    `maven-publish`
}

dependencies {
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    compileOnly(libs.spring.boot.autoconfigure)
    compileOnly(libs.spring.boot.starter.oauth2.authorization.server)
    compileOnly(libs.spring.context)
    compileOnly(libs.nimbus.jose.jwt)
    compileOnly(libs.spring.vault.core)
    compileOnly(libs.spring.security.oauth2.jose)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.assertj.core)
    testImplementation(libs.spring.boot.autoconfigure)
    testImplementation(libs.spring.boot.starter.oauth2.authorization.server)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.nimbus.jose.jwt)
    testImplementation(libs.spring.vault.core)
    testImplementation(libs.spring.security.oauth2.jose)
    testRuntimeOnly(libs.junit.platform.launcher)
}
