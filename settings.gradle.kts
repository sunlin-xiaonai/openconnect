pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "com.android.application" -> {
                    useModule("com.android.tools.build:gradle:${requested.version}")
                }
                "org.jetbrains.kotlin.android" -> {
                    useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")
                }
                "org.jetbrains.kotlin.plugin.serialization" -> {
                    useModule("org.jetbrains.kotlin:kotlin-serialization:${requested.version}")
                }
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AgmenteAndroid"
include(":app")
