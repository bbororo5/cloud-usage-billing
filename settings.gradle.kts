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
    }
}

rootProject.name = "cloud-usage-billing"

include(
    "apps:usage-generator",
    "tests:contracts",
    "tests:postgres-access",
    "tests:module-boundaries",
    "apps:settlement-batch",
    "apps:billing-bff"
)
