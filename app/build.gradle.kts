plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.needlerom.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.needlerom.app"
        minSdk = 26
        targetSdk = 35
        // Every CI build gets a higher version, so each new APK installs as an update.
        versionCode = 100 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "2.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "dev"}"
    }

    // One fixed key for every build (it's not a secret): Android only installs an update over
    // the app, keeping its projects, when the signature matches the installed one.
    signingConfigs {
        create("needlerom") {
            storeFile = file("needlerom.keystore")
            storePassword = "needlerom"
            keyAlias = "needlerom"
            keyPassword = "needlerom"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("needlerom")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("needlerom")
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
    implementation(project(":engine"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
