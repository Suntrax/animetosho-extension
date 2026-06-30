plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.blissless.tensei_extension_template"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.blissless.tensei_extension_template"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            // To use this template, place your keystore in app/release.jks
            storeFile = file("release.jks")
            storePassword = "YOUR_PASSWORD"
            keyAlias = "YOUR_ALIAS"
            keyPassword = "YOUR_PASSWORD"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
    // Empty! We only use built-in Android APIs to keep APK size under 50KB.
}