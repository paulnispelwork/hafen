import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing comes from a file outside the repo (keystore.local points to it). Without it, release builds are unsigned.
val signingProps = Properties().also { props ->
    val pointer = rootProject.file("keystore.local")
    if (pointer.exists()) {
        val path = Properties().apply { pointer.inputStream().use { load(it) } }.getProperty("hafen.signing")
        if (path != null && file(path).exists()) file(path).inputStream().use { props.load(it) }
    }
}

android {
    namespace = "dev.anchor"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.anchor"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.9.0"
    }
    signingConfigs {
        if (signingProps.getProperty("storeFile") != null) create("release") {
            storeFile = file(signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug {
            // test-only: can only be installed over adb, and adb can remove it again (the developer's safety net)
            manifestPlaceholders["testOnly"] = "true"
        }
        release {
            // installable from a downloaded APK; no adb back door
            manifestPlaceholders["testOnly"] = "false"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
}
