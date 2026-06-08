plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":events"))

    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.jackson.module.kotlin)

    compileOnly(libs.spring.boot.autoconfigure)
    compileOnly(libs.spring.boot.starter.amqp)
    compileOnly(libs.spring.context)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.assertj.core)
    testImplementation(libs.spring.boot.autoconfigure)
    testImplementation(libs.spring.boot.starter.amqp)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
