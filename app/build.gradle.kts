import java.util.Properties
import java.net.URI
import java.util.Base64
import groovy.json.JsonSlurper

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

val validateSupabaseClientConfiguration by tasks.registering {
    doLast {
        val supabaseUrl = localProperties.getProperty("supabase.url", "").trim()
        val clientKey = localProperties.getProperty("supabase.publishableKey", "").trim()
        val parsedUrl = runCatching { URI(supabaseUrl) }.getOrNull()
        val validUrl = parsedUrl?.scheme.equals("https", ignoreCase = true) &&
            !parsedUrl?.host.isNullOrBlank() && parsedUrl?.userInfo == null &&
            parsedUrl?.query == null && parsedUrl?.fragment == null
        val validPublishableKey = clientKey.matches(Regex("sb_publishable_[A-Za-z0-9_-]+"))
        val legacyRole = runCatching {
            val segments = clientKey.split('.')
            if (segments.size != 3) null
            else (JsonSlurper().parseText(String(Base64.getUrlDecoder().decode(segments[1]))) as? Map<*, *>)?.get("role") as? String
        }.getOrNull()
        val validLegacyAnonKey = legacyRole == "anon"

        check(validUrl) { "Missing or invalid supabase.url in ignored local.properties" }
        check(validPublishableKey || validLegacyAnonKey) {
            "Missing or invalid supabase.publishableKey in ignored local.properties; use a publishable or legacy anon key"
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(validateSupabaseClientConfiguration)
}

plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
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
        buildConfigField("String", "HAILTONE_DEVICE_ID", "\"${localBuildConfigValue("pupsikcall.deviceId")}\"")
        buildConfigField("String", "HAILTONE_TURN_URL", "\"${localBuildConfigValue("pupsikcall.turn.url")}\"")
        buildConfigField("String", "HAILTONE_TURN_USERNAME", "\"${localBuildConfigValue("pupsikcall.turn.username")}\"")
        buildConfigField("String", "HAILTONE_TURN_CREDENTIAL", "\"${localBuildConfigValue("pupsikcall.turn.credential")}\"")
        buildConfigField("boolean", "HAILTONE_DEBUG_FORCE_RELAY", localProperties.getProperty("pupsikcall.debug.forceRelay", "false").toBoolean().toString())
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
    implementation("com.google.firebase:firebase-messaging:24.1.2")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material3:material3")
    implementation("io.github.webrtc-sdk:android:150.7871.01")
    implementation("io.github.jan-tennert.supabase:auth-kt:3.2.6")
    implementation("com.googlecode.libphonenumber:libphonenumber:8.13.55")
    implementation("io.github.jan-tennert.supabase:postgrest-kt:3.2.6")
    implementation("io.github.jan-tennert.supabase:realtime-kt:3.2.6")
    implementation("io.ktor:ktor-client-okhttp:3.3.1")
    testImplementation("junit:junit:4.13.2")
}