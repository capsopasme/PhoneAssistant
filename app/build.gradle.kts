import java.util.Base64
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Release signing from the environment (same variable names as the fcitx5-android voice CI):
 *   SIGN_KEY_BASE64  base64 of a .jks keystore
 *   SIGN_KEY_ALIAS   key alias
 *   SIGN_KEY_PWD     keystore / key password
 * Without them the release build is signed with the debug key.
 */
val signKeyBase64: String? = System.getenv("SIGN_KEY_BASE64")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.capsopasme.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.capsopasme.assistant"
        minSdk = 34
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
        ndk {
            // QNN / sherpa-onnx native libraries are only built for arm64
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (signKeyBase64 != null) {
            create("release") {
                val file = layout.buildDirectory.file("signing/release.jks").get().asFile
                file.parentFile.mkdirs()
                file.writeBytes(Base64.getDecoder().decode(signKeyBase64))
                storeFile = file
                storePassword = System.getenv("SIGN_KEY_PWD")
                keyAlias = System.getenv("SIGN_KEY_ALIAS")
                keyPassword = System.getenv("SIGN_KEY_PWD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    packaging {
        jniLibs {
            // QNN needs the libraries as real files in nativeLibraryDir:
            // ADSP_LIBRARY_PATH points the DSP there to load libQnnHtpV75Skel.so
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
