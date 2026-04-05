plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.thinkoff.clawwatch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.thinkoff.clawwatch"
        minSdk = 30
        targetSdk = 34
        versionCode = 4
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
        viewBinding = true
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("com.thinkoff.core.ui:core-ui:1.0")

    implementation(libs.security.crypto)
    implementation(libs.google.play.services.auth)
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation(libs.kotlinx.coroutines.android)

    // Vosk STT
    implementation(libs.vosk.android)

    // Health Connect
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")

    // Wearable API (phone ↔ watch messaging)
    implementation(libs.play.services.wearable)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // LiteRT-LM for on-device Gemma 4 E2B inference
    // Using MediaPipe tasks-genai which is compatible with our Kotlin version
    implementation("com.google.mediapipe:tasks-genai:0.10.27")
}
