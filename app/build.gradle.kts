
import java.io.File
import com.android.build.api.variant.HostTestBuilder
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
        versionCode = 6
        versionName = "1.0.1"
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
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            matchingFallbacks += listOf()
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
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
        // libpag's obsolete armeabi copies duplicate its ARMv7 binaries and are unsupported by the NDK.
        jniLibs.excludes += "**/armeabi/**"
        resources {
            merges += "META-INF/license/**"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/*.properties"
            excludes += "META-INF/native-image/**"
            excludes += "org/fusesource/jansi/internal/native/**"
        }
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("debug")) { variant ->
        variant.enable = false
    }
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.hostTests.getValue(HostTestBuilder.UNIT_TEST_TYPE).enable = true
    }
}

val requireReleaseSigning = tasks.register("requireReleaseSigning") {
    doLast {
        check(providers.gradlePropertiesPrefixedBy("android.injected.signing.").get().isEmpty()) {
            "Signing overrides are not allowed. Use the existing official SIGNING_* inputs."
        }
        check(hasReleaseSigningCredentials) {
            "Official release signing credentials are required. Configure the existing SIGNING_* inputs."
        }
    }
}

// AGP skips signing validation for incomplete configurations and can otherwise package an unsigned APK.
tasks.configureEach {
    if (name == "packageRelease" || name == "signReleaseBundle") {
        dependsOn(requireReleaseSigning)
    }
    // Bundle-to-APK conversion is a separate packaging path from native ABI splits.
    if (name in setOf(
            "packageReleaseUniversalApk",
            "makeApkFromBundleForRelease",
            "extractApksFromBundleForRelease",
            "extractApksForRelease",
        )) {
        enabled = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

configurations.configureEach {
    if (name == "releaseUnitTestCompileClasspath" || name == "releaseUnitTestRuntimeClasspath") {
        resolutionStrategy.eachDependency {
            // Local transport tests run on a JVM without Android's logging implementation.
            if (requested.group == "com.squareup.okhttp3" && requested.name == "okhttp") {
                useTarget("${requested.group}:okhttp-jvm:${requested.version}")
            }
        }
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
    implementation(libs.ktor.server.netty) {
        // Transfers use HTTPS/WSS over TCP. Keep Ktor's QUIC Java types for linkage,
        // but omit unused native classifiers unless HTTP/3 is explicitly enabled.
        exclude(group = "io.netty", module = "netty-codec-native-quic")
    }
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
    testRuntimeOnly(libs.slf4j.jdk14)
}
