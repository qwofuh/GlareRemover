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
        mavenCentral()                  // OpenCV 4.10.0 здесь
        //maven("https://storage.googleapis.com/mediapipe/maven")  // только для MediaPipe
    }
}

rootProject.name = "GlareRemover"
include(":app")