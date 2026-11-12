plugins {
    id("com.android.application")
}

android {
    namespace = "com.frak89.cloudyscanner"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.frak89.cloudyscanner"
        minSdk = 31
        targetSdk = 37
        versionCode = 2
        versionName = "1.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // Chiave fissa nel repo: ogni build si installa sopra la precedente senza disinstallare.
        create("shared") {
            storeFile = rootProject.file("signing/cloudy.jks")
            storePassword = "cloudyscanner"
            keyAlias = "cloudy"
            keyPassword = "cloudyscanner"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
}

dependencies {
    implementation("com.google.ar:core:1.50.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")
    testImplementation("junit:junit:4.13.2")
}
