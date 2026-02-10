import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "app.solarma"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.solarma"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.2.4"

        val devnetRpc = (project.findProperty("SOLANA_RPC_DEVNET") as String?) ?: ""
        val mainnetRpc = (project.findProperty("SOLANA_RPC_MAINNET") as String?) ?: ""
        buildConfigField("String", "SOLANA_RPC_DEVNET", "\"$devnetRpc\"")
        buildConfigField("String", "SOLANA_RPC_MAINNET", "\"$mainnetRpc\"")

        // Priority fees / compute budget for critical transactions (ACK/CLAIM/SLASH/SWEEP).
        // Values can be overridden via gradle.properties or CI env vars.
        val cuLimitCritical = (project.findProperty("SOLARMA_CU_LIMIT_CRITICAL") as String?) ?: "200000"
        val cuPriceMicrolamportsRaw =
            (project.findProperty("SOLARMA_CU_PRICE_MICROLAMPORTS") as String?) ?: "1000"
        val cuPriceMicrolamports =
            if (cuPriceMicrolamportsRaw.endsWith("L")) cuPriceMicrolamportsRaw else "${cuPriceMicrolamportsRaw}L"
        buildConfigField("int", "SOLARMA_CU_LIMIT_CRITICAL", cuLimitCritical)
        buildConfigField("long", "SOLARMA_CU_PRICE_MICROLAMPORTS", cuPriceMicrolamports)

        // RPC fan-out send: submit the same signed bytes to multiple endpoints.
        val rpcFanout = (project.findProperty("SOLARMA_RPC_FANOUT") as String?) ?: "3"
        val rpcConfirmTimeoutMs = (project.findProperty("SOLARMA_RPC_CONFIRM_TIMEOUT_MS") as String?) ?: "15000"
        buildConfigField("int", "SOLARMA_RPC_FANOUT", rpcFanout)
        buildConfigField("long", "SOLARMA_RPC_CONFIRM_TIMEOUT_MS", "${rpcConfirmTimeoutMs}L")

        // Optional: attestation server (permit signing for ack_awake_attested).
        val attestationUrl = (project.findProperty("SOLARMA_ATTESTATION_SERVER_URL") as String?) ?: ""
        buildConfigField("String", "SOLARMA_ATTESTATION_SERVER_URL", "\"$attestationUrl\"")

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
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
