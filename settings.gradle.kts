pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val offlineMirror = gradle.gradleUserHomeDir.resolve("manual/offline-maven")
        if (offlineMirror.isDirectory) {
            maven { url = uri(offlineMirror) }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "BciGlasses"
include(":app")
include(":stimulus-app")
