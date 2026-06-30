pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // Local development of the shared dev.jorisjonkers convention plugins.
        mavenLocal()
        // Published convention plugins (dev.jorisjonkers.*). Credentials come from the
        // GITHUB_ACTOR/GITHUB_TOKEN environment in CI (a token minted from the release App,
        // which has cross-repo package read).
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/JorisJonkers-dev/gradle-conventions")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.token").orNull ?: System.getenv("GITHUB_TOKEN")
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
