// ============================================================================
// Pulse Player — app module
//
// Targets:  minSdk 24 (Android 7.0) → covers the vast majority of devices,
//           targetSdk 35 so Media3, foreground-service types and notification
//           permissions all behave per current Play requirements.
//
// Dependencies are intentionally minimal and offline-friendly:
//   Media3 (ExoPlayer + Session)  → real Android audio architecture
//   Room                          → local library cache, favorites, play stats
//   DataStore                     → settings + resume state
//   WebView                       → the Pulse Player UI
// No ad SDK, no analytics SDK, no crash-reporting SDK.
// ============================================================================
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.pulse.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pulse.player"
        minSdk = 24
        targetSdk = 35

        versionCode = 4
        versionName = "1.0.3"

        resourceConfigurations += setOf("en")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Expose build values to the UI layer (shown on the Settings screen).
        buildConfigField("String", "PULSE_VERSION", "\"$versionName\"")
        buildConfigField("boolean", "PULSE_ADS_ENABLED", "${project.findProperty("pulseAdsEnabled") ?: "false"}")
        // Public Audius client API key only. Never place the Audius Bearer Token in this app.
        val audiusApiKey = (project.findProperty("audiusApiKey") as String?)
            ?.trim()
            .orEmpty()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "AUDIUS_API_KEY", "\"$audiusApiKey\"")

        // Optional YouTube Data API v3 key for YouTube catalogue search.
        val youtubeApiKey = (project.findProperty("youtubeApiKey") as String?)
            ?.trim()
            .orEmpty()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "YOUTUBE_API_KEY", "\"$youtubeApiKey\"")
    }

    signingConfigs {
        // Release signing is optional: if keystore/pulse.properties is missing the
        // release build simply stays unsigned and can be signed with apksigner.
        create("release") {
            val propsFile = rootProject.file("keystore/pulse.properties")
            if (propsFile.exists()) {
                val props = Properties().apply { propsFile.inputStream().use { load(it) } }
                storeFile = rootProject.file(props["storeFile"] as String)
                storePassword = props["storePassword"] as String
                keyAlias = props["storeAlias"] as String
                keyPassword = props["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            buildConfigField("boolean", "PULSE_WEB_MOCK", "true")
        }
        release {
            // Stability-first release: the app uses a WebView JS bridge, Room, and
            // Media3 reflection/metadata paths. Keep R8 off until a signed build
            // has been exercised on a second device.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField(
                "boolean",
                "PULSE_WEB_MOCK",
                "${(project.findProperty("pulseWebMock") as String?) != "false"}"
            )
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=androidx.media3.common.util.UnstableApi")
    }

    buildFeatures {
        buildConfig = true
    }

    ksp {
        // Room schema history — committed so migrations stay reviewable.
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*"
        )
    }
}

// ── Web frontend → assets ─────────────────────────────────────────────────
// `web/` is the single source of truth for the UI. This task copies it into the
// APK assets before every build, so you never edit a duplicated copy.
// Release builds can drop the browser-only mock engine with -PpulseWebMock=false.
val webDir: File = rootProject.file("../web")
val includeWebMock: Boolean = (project.findProperty("pulseWebMock") as String?) != "false"

val syncWebAssets by tasks.registering(Sync::class) {
    description = "Copies the Pulse Player web frontend into the app assets when the optional ../web checkout exists."
    group = "pulse"
    if (webDir.exists()) {
        from(webDir) {
            if (!includeWebMock) exclude("js/mock/**")
            exclude(".*")
        }
    }
    into(layout.projectDirectory.dir("src/main/assets/web"))
}

tasks.named("preBuild") { dependsOn(syncWebAssets) }
tasks.named("clean") {
    doFirst {
        if (webDir.exists()) delete(layout.projectDirectory.dir("src/main/assets/web"))
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.guava)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
}
