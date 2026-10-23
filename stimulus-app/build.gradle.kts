import org.gradle.api.tasks.testing.Test

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

layout.buildDirectory.set(
    gradle.gradleUserHomeDir.resolve(
        "bci-glasses-build/${rootProject.projectDir.name}/${rootProject.name}/${project.name}"
    )
)

android {
    namespace = "com.bci.glasses.stimulus"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bci.glasses.stimulus"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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
}

tasks.withType<Test>().configureEach {
    val variantName = name.removePrefix("test").removeSuffix("UnitTest")
        .replaceFirstChar { it.lowercase() }
    classpath += files(layout.buildDirectory.dir("tmp/kotlin-classes/${variantName}UnitTest"))
}

val copyStimulusDebugApk by tasks.registering(Copy::class) {
    from(layout.buildDirectory.file("outputs/apk/debug/stimulus-app-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("artifacts"))
    rename { "bci-stimulus-display-debug.apk" }
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(copyStimulusDebugApk)
}
