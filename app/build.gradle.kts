import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.edgeledger.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.edgeledger.app"
        minSdk = 26
        targetSdk = 36
        val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        val overrideVc = (project.findProperty("versionCodeOverride") as? String)?.toIntOrNull()
        versionCode = overrideVc ?: (807000 + ciRunNumber)
        versionName = "8.7.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val storePath = System.getenv("RELEASE_STORE_FILE")
            val storePass = System.getenv("RELEASE_STORE_PASSWORD")
            val alias = System.getenv("RELEASE_KEY_ALIAS")
            val keyPass = System.getenv("RELEASE_KEY_PASSWORD")
            val bundledKeystore = file("edgeledger-update.jks")
            when {
                !storePath.isNullOrBlank() && file(storePath).exists() -> {
                    require(!storePass.isNullOrBlank() && !alias.isNullOrBlank() && !keyPass.isNullOrBlank()) {
                        "RELEASE_STORE_FILE is set, but RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, or RELEASE_KEY_PASSWORD is missing."
                    }
                    storeFile = file(storePath)
                    storePassword = storePass
                    keyAlias = alias
                    keyPassword = keyPass
                }
                bundledKeystore.exists() -> {
                    // Persistent project keystore bundled for zero-config in-place APK updates
                    storeFile = bundledKeystore
                    storePassword = "edgeledger_inplace_v1"
                    keyAlias = "edgeledger_update"
                    keyPassword = "edgeledger_inplace_v1"
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
            val relConfig = signingConfigs.getByName("release")
            signingConfig = when {
                relConfig.storeFile != null -> relConfig
                System.getenv("REQUIRE_RELEASE_SIGNING") == "true" ->
                    throw GradleException(
                        "Release signing keystore is required (RELEASE_STORE_FILE missing). " +
                            "Run scripts/setup-release-keystore.sh and configure GitHub Actions secrets."
                    )
                else -> signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
        // Allow external dependency version checks without failing offline/pinned CI
        disable += setOf("GradleDependency", "NewerVersionAvailable")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Offline-first SQLite via Room (2.7.0 supports Kotlin 2.1 + KSP 2.1)
    val roomVersion = "2.7.0"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Zero-flash Theme & Settings persistence
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Weekly Background Sync
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Material 3 Home-Screen Widget (Jetpack Glance)
    implementation("androidx.glance:glance-appwidget:1.1.1")

    // Live Team Logo Rendering & Offline Disk Cache (PNG + SVG)
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")

    // Networking & JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Unit Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// Prevent parallel KSP tasks (:kspDebugKotlin & :kspReleaseKotlin) from racing on Room's shared schema JSON
tasks.matching { it.name == "kspReleaseKotlin" || it.name == "kspDebugUnitTestKotlin" || it.name == "kspReleaseUnitTestKotlin" }.configureEach {
    mustRunAfter("kspDebugKotlin")
}
