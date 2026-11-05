plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.laur.waypoint"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.laur.waypointapp" // own id, installs next to Waypoint beta
        minSdk = 26
        targetSdk = 34
        // Each CI build gets a higher number, so Android always treats it as an update.
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        versionCode = 100 + run
        versionName = "1.1.$run"
        // Mapillary street photos: token from the MAPILLARY_TOKEN secret. Without it the app builds and simply skips Mapillary.
        val mapillary = (System.getenv("MAPILLARY_TOKEN") ?: "").trim().replace("\"", "")
        buildConfigField("String", "MAPILLARY_TOKEN", "\"$mapillary\"")
    }

    signingConfigs {
        // Key lives in app/waypoint.p12, encrypted (AES-256). Its password is only in the
        // GitHub secret SIGNING_PASSWORD, so the key file is useless to anyone else.
        create("trip") {
            val pw = System.getenv("SIGNING_PASSWORD")?.trim()?.takeIf { it.isNotEmpty() }
                ?: error("SIGNING_PASSWORD is not set (add it under repo Settings > Secrets and variables > Actions)")
            storeFile = file("waypoint.p12")
            storeType = "PKCS12"
            storePassword = pw
            keyAlias = "app"
            keyPassword = pw
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("trip")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("trip")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
