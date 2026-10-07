import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Release signing reads key.properties (gitignored; same keystore as the Flutter
// builds so the APK installs as an update). Missing file = debug signing.
val keystoreProperties = Properties().apply {
    rootProject.file("key.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.sarvarbek.expense_tracker"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sarvarbek.expense_tracker"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "2.0.0"
    }

    signingConfigs {
        create("release") {
            keyAlias = keystoreProperties["keyAlias"] as String?
            keyPassword = keystoreProperties["keyPassword"] as String?
            storeFile = (keystoreProperties["storeFile"] as String?)?.let { file(it) }
            storePassword = keystoreProperties["storePassword"] as String?
        }
    }

    buildTypes {
        // Debug installs beside the real app on the same phone.
        debug {
            applicationIdSuffix = ".dev"
            resValue("string", "app_name", "Xarajatlar dev")
        }
        release {
            resValue("string", "app_name", "Xarajatlar")
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (keystoreProperties.isEmpty) "debug" else "release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time on minSdk 24.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        resValues = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.functions)
    implementation(libs.ktor.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.lifecycle.process)
    coreLibraryDesugaring(libs.desugar)
    debugImplementation(libs.compose.tooling)
    debugImplementation(libs.compose.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.test.junit4)
}
