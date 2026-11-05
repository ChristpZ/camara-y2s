plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.camaray2s"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.camaray2s"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
