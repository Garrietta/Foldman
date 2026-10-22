pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
    versionCatalogs {
        create("kodaLibs") {
            from(files("gradle/koda.versions.toml"))
        }
    }
}
rootProject.name = "foldman"
include(":app", ":koda")
