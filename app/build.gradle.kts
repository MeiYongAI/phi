import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.meiyongai.phi"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.meiyongai.phi"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    val releaseSigningPropertiesFile = rootProject.file("keystore/keystore.properties")
    val releaseSigningProperties = Properties()
    val hasReleaseSigning = releaseSigningPropertiesFile.isFile
    if (hasReleaseSigning) {
        releaseSigningPropertiesFile.inputStream().use { stream ->
            releaseSigningProperties.load(stream)
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseSigningProperties.getProperty("storeFile")))
                storePassword = requireNotNull(releaseSigningProperties.getProperty("storePassword"))
                keyAlias = requireNotNull(releaseSigningProperties.getProperty("keyAlias"))
                keyPassword = requireNotNull(releaseSigningProperties.getProperty("keyPassword"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    compileOnly(libs.libxposed.service)
    implementation(libs.androidx.core.ktx)
    implementation("net.java.dev.jna:jna:5.16.0@aar")
}
