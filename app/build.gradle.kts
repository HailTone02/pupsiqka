import java.util.Properties

val localProperties = Properties().apply {
    val configFile = rootProject.file("local.properties")
    if (configFile.isFile) configFile.inputStream().use { load(it) }
}

fun localBuildConfigValue(propertyName: String): String {
    return localProperties.getProperty(propertyName, "")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "")
        .replace("\r", "")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.pupsikcall.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pupsikcall.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
        buildConfigField("String", "SUPABASE_URL", "\"${localBuildConfigValue("supabase.url")}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${localBuildConfigValue("supabase.publishableKey")}\"")
        buildConfigField("String", "PUPSIKCALL_DEVICE_ID", "\"${localBuildConfigValue("pupsikcall.deviceId")}\"")
        buildConfigField("String", "PUPSIKCALL_TURN_URL", "\"${localBuildConfigValue("pupsikcall.turn.url")}\"")
        buildConfigField("String", "PUPSIKCALL_TURN_USERNAME", "\"${localBuildConfigValue("pupsikcall.turn.username")}\"")
        buildConfigField("String", "PUPSIKCALL_TURN_CREDENTIAL", "\"${localBuildConfigValue("pupsikcall.turn.credential")}\"")
        buildConfigField("boolean", "PUPSIKCALL_DEBUG_FORCE_RELAY", localProperties.getProperty("pupsikcall.debug.forceRelay", "false").toBoolean().toString())
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.00")
    implementation(composeBom)
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material3:material3")
    implementation("io.github.webrtc-sdk:android:150.7871.01")
    implementation("io.github.jan-tennert.supabase:auth-kt:3.2.6")
    implementation("io.github.jan-tennert.supabase:realtime-kt:3.2.6")
    implementation("io.ktor:ktor-client-okhttp:3.3.1")
    testImplementation("junit:junit:4.13.2")
}