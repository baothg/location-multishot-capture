plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

group = "com.thgbao.location_multishot_capture"
version = "1.0.0"

android {
    namespace = "com.thgbao.location_multishot_capture"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        minSdk = 24
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
