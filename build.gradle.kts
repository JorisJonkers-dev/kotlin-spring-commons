import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
}

allprojects {
    group = "dev.jorisjonkers"
    version =
        providers
            .gradleProperty("version")
            .orElse(
                providers
                    .environmentVariable("GITHUB_REF_NAME")
                    .map { it.removePrefix("v") },
            ).orElse("0.1.0")
            .get()
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(plugin = "jacoco")

        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
            withJavadocJar()
            withSourcesJar()
        }

        tasks.withType<KotlinCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            }
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            jvmArgs("-Xshare:off")
            finalizedBy(tasks.named("jacocoTestReport"))

            // Make test execution visible: log skipped/failed events (with full stack traces) and
            // print a per-module summary line so a successful run is never silent.
            testLogging {
                events(TestLogEvent.SKIPPED, TestLogEvent.FAILED)
                exceptionFormat = TestExceptionFormat.FULL
                showExceptions = true
                showCauses = true
                showStackTraces = true
            }
            val moduleName = project.name
            addTestListener(
                object : TestListener {
                    override fun beforeSuite(suite: TestDescriptor) = Unit

                    override fun beforeTest(test: TestDescriptor) = Unit

                    override fun afterTest(test: TestDescriptor, result: TestResult) = Unit

                    override fun afterSuite(suite: TestDescriptor, result: TestResult) {
                        // Only the root suite of each module (no parent) carries the totals.
                        if (suite.parent == null) {
                            logger.lifecycle(
                                "Test summary [$moduleName]: ${result.testCount} tests, " +
                                    "${result.successfulTestCount} passed, " +
                                    "${result.failedTestCount} failed, " +
                                    "${result.skippedTestCount} skipped (${result.resultType}).",
                            )
                        }
                    }
                },
            )
        }

        tasks.named<JacocoReport>("jacocoTestReport") {
            dependsOn(tasks.named("test"))
            reports {
                xml.required.set(true)
                html.required.set(true)
            }
        }

        tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
            dependsOn(tasks.named("test"))
            violationRules {
                rule {
                    limit {
                        counter = "LINE"
                        value = "COVEREDRATIO"
                        minimum = "0.80".toBigDecimal()
                    }
                }
            }
        }

        tasks.named("check") {
            dependsOn(tasks.named("jacocoTestCoverageVerification"))
        }
    }

    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("mavenJava") {
                    from(components["java"])
                    artifactId = "kotlin-commons-${project.name}"
                    pom {
                        name.set("kotlin-commons-${project.name}")
                        description.set("Kotlin/Spring commons module ${project.name}")
                        url.set("https://github.com/JorisJonkers-dev/kotlin-spring-commons")
                        licenses {
                            license {
                                name.set("Joris Jonkers Proprietary Source-Available License 1.0")
                                url.set(
                                    "https://github.com/JorisJonkers-dev/kotlin-spring-commons/blob/main/LICENSE",
                                )
                                distribution.set("repo")
                                comments.set("SPDX-License-Identifier: LicenseRef-JorisJonkers-Proprietary-1.0")
                            }
                        }
                        developers {
                            developer {
                                id.set("jorisjonkers-dev")
                                name.set("JorisJonkers-dev")
                            }
                        }
                        scm {
                            connection.set("scm:git:https://github.com/JorisJonkers-dev/kotlin-spring-commons.git")
                            developerConnection.set(
                                "scm:git:ssh://git@github.com:JorisJonkers-dev/kotlin-spring-commons.git",
                            )
                            url.set("https://github.com/JorisJonkers-dev/kotlin-spring-commons")
                        }
                    }
                }
            }
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/JorisJonkers-dev/kotlin-spring-commons")
                    credentials {
                        username = providers.environmentVariable("GITHUB_ACTOR").orNull
                        password = providers.environmentVariable("GITHUB_TOKEN").orNull
                    }
                }
            }
        }
    }
}
