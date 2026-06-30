pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // Local development of the shared dev.jorisjonkers convention plugins.
        mavenLocal()
        maven {
            name = "JorisJonkersDevGradleConventions"
            url = uri("https://maven.pkg.github.com/JorisJonkers-dev/gradle-conventions")
            credentials {
                username =
                    providers
                        .gradleProperty("gpr.user")
                        .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                        .orNull
                password =
                    providers
                        .gradleProperty("gpr.token")
                        .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                        .orNull
            }
        }
    }
    // The convention plugins are published as `gradle-conventions-<name>` artifacts; map the
    // dev.jorisjonkers.* plugin ids onto them instead of resolving a plugin marker.
    resolutionStrategy {
        eachPlugin {
            val id = requested.id.id
            if (id.startsWith("dev.jorisjonkers.")) {
                useModule(
                    "dev.jorisjonkers:gradle-conventions-${id.removePrefix("dev.jorisjonkers.")}:${requested.version}",
                )
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.spring.io/milestone")
    }
}

rootProject.name = "kotlin-spring-commons"

include(
    ":archunit-test",
    ":blocks",
    ":command",
    ":crac",
    ":email",
    ":events",
    ":exceptions",
    ":identity",
    ":messaging",
    ":observability",
    ":sync-core",
    ":sync-spring",
    ":system-test-harness",
    ":test-support",
    ":timing",
    ":vault",
    ":web",
)
