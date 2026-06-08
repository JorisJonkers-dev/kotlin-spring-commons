import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
}

allprojects {
    group = "dev.extratoast"
    version =
        providers.gradleProperty("version")
            .orElse(
                providers.environmentVariable("GITHUB_REF_NAME")
                    .map { it.removePrefix("v") },
            )
            .orElse("0.1.0")
            .get()
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
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
                        url.set("https://github.com/ExtraToast/kotlin-spring-commons")
                        licenses {
                            license {
                                name.set("MIT")
                                url.set("https://opensource.org/licenses/MIT")
                            }
                        }
                        developers {
                            developer {
                                id.set("extratoast")
                                name.set("ExtraToast")
                            }
                        }
                        scm {
                            connection.set("scm:git:https://github.com/ExtraToast/kotlin-spring-commons.git")
                            developerConnection.set("scm:git:ssh://git@github.com:ExtraToast/kotlin-spring-commons.git")
                            url.set("https://github.com/ExtraToast/kotlin-spring-commons")
                        }
                    }
                }
            }
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/ExtraToast/kotlin-spring-commons")
                    credentials {
                        username = providers.environmentVariable("GITHUB_ACTOR").orNull
                        password = providers.environmentVariable("GITHUB_TOKEN").orNull
                    }
                }
            }
        }
    }
}
