
import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
}

fun signingValue(name: String): String? =
    providers.environmentVariable(name)
        .orElse(providers.gradleProperty(name))
        .orNull

val signingKeystorePath = signingValue("SIGNING_KEYSTORE_PATH")
val signingStorePassword = signingValue("SIGNING_STORE_PASSWORD")
val signingKeyAlias = signingValue("SIGNING_KEY_ALIAS")
val signingKeyPassword = signingValue("SIGNING_KEY_PASSWORD")
val hasReleaseSigningCredentials =
    !signingKeystorePath.isNullOrBlank() &&
        !signingStorePassword.isNullOrBlank() &&
        !signingKeyAlias.isNullOrBlank() &&
        !signingKeyPassword.isNullOrBlank() &&
        File(signingKeystorePath).isFile

android {
    namespace = "me.pipi.easyshare"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.pipi.easyshare"
        minSdk = 31
        targetSdk = 37
        versionCode = 4
        versionName = "0.4"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigningCredentials) {
                storeFile = file(signingKeystorePath!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (hasReleaseSigningCredentials) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            matchingFallbacks += listOf()
        }
        debug {
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
        resValues = true
    }

    packaging {
        resources {
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/*.properties"
            excludes += "META-INF/native-image/**"
            excludes += "org/fusesource/jansi/internal/native/**"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.client)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.network.tls.certificates)

    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.documentfile)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.libpag)

    testImplementation(libs.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
