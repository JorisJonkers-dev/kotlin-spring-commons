plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    `java-library`
    `maven-publish`
}

dependencies {
    compileOnly(libs.spring.boot.autoconfigure)
    compileOnly(libs.spring.boot.starter.mail)
    compileOnly(libs.spring.context)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.assertj.core)
    testImplementation(libs.spring.boot.starter.mail)
    // ApplicationContextRunner, plus the mail auto-configuration, so the
    // registration test can reproduce scan-then-auto-configure ordering.
    testImplementation(libs.spring.boot.autoconfigure)
    testImplementation(libs.spring.boot.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
