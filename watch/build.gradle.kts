import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}


// Release signing from keystore.properties (gitignored; see README).
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.thinkoff.clawwatch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.thinkoff.clawwatch"
        minSdk = 30
        targetSdk = 36
        versionCode = 8
        versionName = "0.3.1"
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // Package the nullclaw binary from assets
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }
}

dependencies {
    // Wear OS
    implementation(libs.wear)
    implementation(libs.wear.input)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)

    // Material (FAB)
    implementation("com.google.android.material:material:1.12.0")

    // AppCompat
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Vosk STT (offline speech recognition)
    implementation(libs.vosk.android)

    // Wearable Data Layer — receive config from phone companion app
    implementation(libs.play.services.wearable)
    // Wear OS push notifications (Phase A server-initiated alerts)
    implementation(libs.firebase.messaging)
    // Encrypted key/config storage on watch
    implementation(libs.security.crypto)

    // Health Connect
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")
}
