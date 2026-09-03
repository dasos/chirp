plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.chirp.wear"
    compileSdk = 35

    signingConfigs {
        create("ci") {
            // Reuse the phone app's keystore so upgrades retain the same signer.
            storeFile = file("../app/signing/debug.keystore")
            storePassword = "android"
            keyAlias = "chirp"
            keyPassword = "android"
        }
    }

    defaultConfig {
        // Wear Data Layer identity must match the phone application ID.
        // Keep the Kotlin namespace separate; the apps are installed on different devices.
        applicationId = "com.chirp"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.5.4"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("ci")
            isMinifyEnabled = false
        }
        release {
            signingConfig = signingConfigs.getByName("ci")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.activity.wear.compose)
    implementation(libs.androidx.wear.compose)
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.navigation)
    implementation(libs.play.services.wearable)

    // Tiles
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.concurrent.futures)

    // Compose (from the shared BOM)
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Misc
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)
}