plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val registryUrl = (project.findProperty("astra.registryUrl") as String?) ?: "http://yoyopi:8000"

android {
    namespace = "dev.astra.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.astra.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0-phone"
        buildConfigField("String", "DEFAULT_REGISTRY_URL", "\"$registryUrl\"")
    }

    // One fixed signing key, so a newer APK (from your PC or from GitHub) installs over the
    // old one and keeps your pairings. It's a debug key for your own phones only; the store
    // and key password are both "android", like Android Studio's own debug key.
    signingConfigs {
        getByName("debug") {
            storeFile = file("astra-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            // MVP installs as a direct APK; sign with your own debug or release key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
