plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.blissless.subsplease"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.blissless.subsplease"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            // Points to /app/release/
            storeFile = file("release")
            storePassword = "lucaacul9"
            keyAlias = "key0"
            keyPassword = "lucaacul9"
        }
    }

    buildTypes {
        release {
            // ENABLE shrinking to strip unused code and reduce APK size
            isMinifyEnabled = true
            isShrinkResources = true

            // Automatically sign with the release keystore
            signingConfig = signingConfigs.getByName("release")

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "src/main/keepRules/rules.keep" // <--- ADDED THIS LINE!
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Intentionally empty!
    // We only use Android's built-in WebView and org.json.
}