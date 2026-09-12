plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun signingValue(propertyName: String, environmentName: String): String? =
    providers.gradleProperty(propertyName).orNull
        ?: providers.environmentVariable(environmentName).orNull

android {
    namespace = "com.betterclouddrive.mobile.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.betterclouddrive.mobile.spike"
        minSdk = 26
        targetSdk = 37
        versionCode = 22
        versionName = "0.22.0-stage21"
        providers.gradleProperty("releaseVersionCode").orNull?.toIntOrNull()?.let { versionCode = it }
        providers.gradleProperty("releaseVersionName").orNull?.let { versionName = it }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    val releaseStoreFile = signingValue("releaseStoreFile", "BCD_RELEASE_STORE_FILE")
    val releaseStorePassword = signingValue("releaseStorePassword", "BCD_RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = signingValue("releaseKeyAlias", "BCD_RELEASE_KEY_ALIAS")
    val releaseKeyPassword = signingValue("releaseKeyPassword", "BCD_RELEASE_KEY_PASSWORD")
    if (listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null }) {
        signingConfigs.create("releaseFromEnvironment") {
            storeFile = file(releaseStoreFile!!)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("releaseFromEnvironment")
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.appcompat)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.datastore.preferences)
    androidTestImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.sqlite.bundled)
    androidTestImplementation(libs.ktor.client.core)
    androidTestImplementation(libs.ktor.client.okhttp)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.foundation)
    androidTestImplementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
