import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.jorisjonkers.testing) apply false
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
        // Shared testing conventions from the dev.jorisjonkers gradle commons: jacoco, test
        // logging, an integration-test source set and coverage verification (LINE >= 0.80 by
        // default; raised per module via the `extratoastTesting` extension where needed).
        apply(plugin = "dev.jorisjonkers.testing")

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
