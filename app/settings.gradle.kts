// The DebDroid app: an installer and a front end for the scripts beside it.
//
// A build of its own, rooted here rather than at the repository root, so the
// shell project stays a plain checkout with no gradle in it.

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
        google()
        mavenCentral()
    }
}

rootProject.name = "debdroid-app"
