pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
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
    ":observability",
    ":test-support",
    ":timing",
    ":vault",
    ":web",
)
