import org.gradle.api.tasks.testing.Test

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Java argument files on Windows can misread non-ASCII build paths used by Gradle test workers.
layout.buildDirectory.set(
    gradle.gradleUserHomeDir.resolve(
        "bci-glasses-build/${rootProject.projectDir.name}/${rootProject.name}/${project.name}"
    )
)

android {
    namespace = "com.bci.glasses"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bci.glasses.prototype"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "0.10.0-prototype"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

tasks.withType<Test>().configureEach {
    val variantName = name.removePrefix("test").removeSuffix("UnitTest")
        .replaceFirstChar { it.lowercase() }
    classpath += files(layout.buildDirectory.dir("tmp/kotlin-classes/${variantName}UnitTest"))
}

val copyDebugApkToProject by tasks.registering(Copy::class) {
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("artifacts"))
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(copyDebugApkToProject)
}
