import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    id("jacoco")
}

android {
    namespace = "app.solarma"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.solarma"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.2.4"

        val devnetRpc = (project.findProperty("SOLANA_RPC_DEVNET") as String?) ?: ""
        val mainnetRpc = (project.findProperty("SOLANA_RPC_MAINNET") as String?) ?: ""
        buildConfigField("String", "SOLANA_RPC_DEVNET", "\"$devnetRpc\"")
        buildConfigField("String", "SOLANA_RPC_MAINNET", "\"$mainnetRpc\"")

        // Network is fixed per build; the app has no runtime network switch.
        // Mainnet builds must name every address explicitly so that a devnet (or
        // compromised) program ID / treasury can never ship in a real-money release:
        //   ./gradlew assembleRelease -PSOLARMA_NETWORK=mainnet \
        //       -PSOLARMA_PROGRAM_ID=<id> -PSOLARMA_TREASURY=<addr> -PSOLANA_RPC_MAINNET=<url>
        val network = ((project.findProperty("SOLARMA_NETWORK") as String?) ?: "devnet").lowercase()
        require(network == "devnet" || network == "mainnet") {
            "SOLARMA_NETWORK must be 'devnet' or 'mainnet', got '$network'"
        }
        val isMainnet = network == "mainnet"
        val base58Key = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")

        fun addressProperty(
            name: String,
            devnetDefault: String,
        ): String {
            val value = (project.findProperty(name) as String?)?.trim()
            val resolved =
                when {
                    !value.isNullOrEmpty() -> value
                    !isMainnet -> devnetDefault
                    else -> throw GradleException("$name is required for SOLARMA_NETWORK=mainnet")
                }
            require(base58Key.matches(resolved)) { "$name is not a valid base58 public key: $resolved" }
            return resolved
        }

        val programId = addressProperty("SOLARMA_PROGRAM_ID", "F54LpWS97bCvkn5PGfUsFi8cU8HyYBZgyozkSkAbAjzP")
        val treasury = addressProperty("SOLARMA_TREASURY", "2g536HdC3ByKNZc6gev6jb6NmFr7dMnTnp66Gqk5rusk")
        if (isMainnet) {
            if (mainnetRpc.isBlank()) {
                throw GradleException("SOLANA_RPC_MAINNET is required for SOLARMA_NETWORK=mainnet")
            }
            // Addresses held on a machine known to be compromised (see docs/AUDIT_AND_MAINNET_PLAN.ru.md).
            val compromised =
                setOf(
                    "F54LpWS97bCvkn5PGfUsFi8cU8HyYBZgyozkSkAbAjzP",
                    "2g536HdC3ByKNZc6gev6jb6NmFr7dMnTnp66Gqk5rusk",
                )
            if (programId in compromised || treasury in compromised) {
                throw GradleException("Refusing to build mainnet with a compromised devnet program ID or treasury address")
            }
        }
        buildConfigField("boolean", "IS_MAINNET", isMainnet.toString())
        buildConfigField("String", "PROGRAM_ID", "\"$programId\"")
        buildConfigField("String", "TREASURY_ADDRESS", "\"$treasury\"")

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
                "proguard-rules.pro",
            )
            val hasSigning =
                keystorePropsFile.exists() &&
                    !keystoreProps.getProperty("STORE_FILE").isNullOrBlank()
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // Never fall back to the debug key: it is an unprotected, per-machine
                // throwaway key, so a debug-signed "release" can't be updated safely.
                // Without a keystore the release APK is left unsigned and cannot be installed.
                logger.warn("Release signing config not found; release output will be UNSIGNED.")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
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

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
    val kotlinClasses =
        fileTree("build/tmp/kotlin-classes/debug") {
            exclude(
                // Android generated
                "**/R.class",
                "**/R$*.class",
                "**/BuildConfig.*",
                "**/Manifest*.*",
                // Hilt / DI generated
                "**/*_Hilt*.*",
                "**/Hilt_*.*",
                "**/*_Factory.*",
                "**/*_MembersInjector.*",
                // Navigation generated
                "**/*Directions*.*",
                "**/*Args*.*",
                // Compose UI screens (require Compose runtime)
                "**/ui/create/CreateAlarmScreen*.*",
                "**/ui/details/AlarmDetailsScreen*.*",
                "**/ui/home/HomeScreen*.*",
                "**/ui/history/HistoryScreen*.*",
                "**/ui/settings/SettingsScreen*.*",
                "**/ui/onboarding/OnboardingScreen*.*",
                "**/ui/components/Components*.*",
                "**/ui/components/QrCameraPreview*.*",
                // Composable + Android Bitmap
                "**/ui/components/QrCodeGenerator*.*",
                // Theme (Compose theming, no logic)
                "**/ui/theme/*.*",
                // Activities & Services (require Android Context)
                "**/MainActivity*.*",
                "**/SolarmaApp*.*",
                "**/alarm/AlarmActivity*.*",
                "**/alarm/AlarmService*.*",
                // BroadcastReceivers & Workers (require Android runtime)
                "**/alarm/AlarmReceiver*.*",
                "**/alarm/BootReceiver*.*",
                "**/alarm/PendingCreateNotificationWorker*.*",
                "**/alarm/RestoreAlarmsWorker*.*",
                "**/alarm/SlashAlarmWorker*.*",
                // Room databases (abstract, generated by KSP)
                "**/data/local/AlarmDatabase*.*",
                "**/data/local/StatsDatabase*.*",
                "**/di/*.*",
                // Navigation (Compose NavHost)
                "**/navigation/Navigation*.*",
                // Hardware-dependent scanners (require sensors/camera)
                "**/wakeproof/NfcScanner*.*",
                "**/wakeproof/QrScanner*.*",
                "**/wakeproof/StepCounter*.*",
                // AlarmScheduler (requires AlarmManager + Context)
                "**/alarm/AlarmScheduler*.*",
            )
        }
    val javaClasses =
        fileTree("build/intermediates/javac/debug/classes") {
            exclude(
                "**/R.class",
                "**/R$*.class",
                "**/BuildConfig.*",
                "**/Manifest*.*",
            )
        }
    classDirectories.setFrom(kotlinClasses, javaClasses)
    sourceDirectories.setFrom("src/main/java", "src/main/kotlin")
    executionData.setFrom(fileTree("build") { include("**/*.exec") })
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
    testImplementation("org.json:json:20260522")
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.espresso)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
