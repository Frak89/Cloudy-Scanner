buildscript {
    dependencies {
        // LiteRT-LM is built with Kotlin 2.4; AGP's bundled Kotlin compiler is older, so pin a matching one.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

plugins {
    id("com.android.application") version "9.1.1" apply false
}
