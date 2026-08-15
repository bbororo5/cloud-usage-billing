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
    "apps:usage-event-api",
    "apps:usage-ledger-writer",
    "apps:settlement-batch",
    "apps:billing-bff"
)
