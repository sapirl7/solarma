import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.solarma"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.solarma"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.2.3"

        val devnetRpc = (project.findProperty("SOLANA_RPC_DEVNET") as String?) ?: ""
        val mainnetRpc = (project.findProperty("SOLANA_RPC_MAINNET") as String?) ?: ""
        buildConfigField("String", "SOLANA_RPC_DEVNET", "\"$devnetRpc\"")
        buildConfigField("String", "SOLANA_RPC_MAINNET", "\"$mainnetRpc\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties()
    if (keystorePropsFile.exists()) {
        keystoreProps.load(FileInputStream(keystorePropsFile))
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                val storeFilePath = keystoreProps.getProperty("STORE_FILE")
                if (!storeFilePath.isNullOrBlank()) {
                    storeFile = rootProject.file(storeFilePath)
                    storePassword = keystoreProps.getProperty("STORE_PASSWORD")
                    keyAlias = keystoreProps.getProperty("KEY_ALIAS")
                    keyPassword = keystoreProps.getProperty("KEY_PASSWORD")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val hasSigning = keystorePropsFile.exists() &&
                !keystoreProps.getProperty("STORE_FILE").isNullOrBlank()
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                signingConfig = signingConfigs.getByName("debug")
                logger.warn("Release signing config not found; using debug signing.")
            }
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }
    
    buildFeatures {
        compose = true
        buildConfig = true
    }
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    
    // Android
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    
    // Lifecycle
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    
    // Navigation
    implementation(libs.navigation.compose)
    
    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation)
    ksp(libs.hilt.compiler)
    
    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    
    // DataStore
    implementation(libs.datastore)
    
    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    
    // Solana Mobile Wallet Adapter
    implementation(libs.mwa.clientlib)
    
    // Solana primitives (for tx building)
    implementation(libs.sol4k)
    implementation(libs.tweetnacl)
    
    // WorkManager (for boot restore)
    implementation(libs.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)
    
    // CameraX (for QR scanning)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    
    // ML Kit Barcode Scanning
    implementation(libs.mlkit.barcode)
    
    // ZXing for QR code generation
    implementation(libs.zxing)
    
    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.espresso)
}
