plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.blissless.animetosho"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.blissless.animetosho"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("release")
            storePassword = "lucaacul9"
            keyAlias = "key0"
            keyPassword = "lucaacul9"
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