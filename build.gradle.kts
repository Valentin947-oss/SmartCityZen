// Top-level build file
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.5.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24")
        classpath("com.google.gms:google-services:4.4.2")
    }
}

// Repositories are declared once, centrally, in settings.gradle.kts
// (dependencyResolutionManagement) — do NOT redeclare them here, since
// repositoriesMode = FAIL_ON_PROJECT_REPOS forbids per-project repositories.

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
