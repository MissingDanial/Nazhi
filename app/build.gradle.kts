import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { input -> load(input) }
    }
    val backendEnvFile = rootProject.file("../nazhi-backend/.env")
    if (backendEnvFile.exists()) {
        backendEnvFile.readLines().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isNotEmpty() && !line.startsWith("#") && "=" in line) {
                val key = line.substringBefore("=").trim()
                val value = line.substringAfter("=").trim()
                putIfAbsent(key, value)
            }
        }
    }
}

fun localConfig(name: String, fallback: String): String {
    return providers.gradleProperty(name).orNull
        ?: providers.environmentVariable(name).orNull
        ?: localProperties.getProperty(name)
        ?: fallback
}

fun escaped(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}

android {
    namespace = "com.nazhi.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nazhi.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField(
            "String",
            "NAZHI_BACKEND_BASE_URL",
            "\"${escaped(localConfig("NAZHI_BACKEND_BASE_URL", "http://10.0.2.2:8787"))}\""
        )
        buildConfigField(
            "String",
            "NAZHI_DEV_TOKEN",
            "\"${escaped(localConfig("NAZHI_DEV_TOKEN", "change-me"))}\""
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")
    val roomVersion = "2.8.4"

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.room:room-ktx:$roomVersion")
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")

    ksp("androidx.room:room-compiler:$roomVersion")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
