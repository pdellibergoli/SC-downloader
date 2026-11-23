import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) {
        f.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.streamingcommunity.downloader"
    compileSdk = 34

    val propVersionName = project.findProperty("appVersionName")?.toString()?.trim()?.removePrefix("v")
        ?.takeIf { it.isNotEmpty() }
        ?: "1.1.1"
    val propVersionCode = project.findProperty("appVersionCode")?.toString()?.toIntOrNull()
        ?: 10101
    val defaultGithubToken = (
        project.findProperty("githubToken")?.toString()?.trim()
            ?: System.getenv("APP_UPDATE_TOKEN")?.trim()
            ?: localProps.getProperty("github.token")?.trim()
            ?: ""
        ).replace("\"", "\\\"")

    defaultConfig {
        applicationId = "com.streamingcommunity.downloader"
        minSdk = 26
        targetSdk = 34
        versionCode = propVersionCode
        versionName = propVersionName

        buildConfigField("String", "DEFAULT_GITHUB_TOKEN", "\"$defaultGithubToken\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("releaseSigning") {
            val ksPath = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() } ?: "sc-signing.jks"
            storeFile = file(ksPath)
            storePassword = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() } ?: "scdownloader123"
            keyAlias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "scdownloader"
            keyPassword = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: "scdownloader123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("releaseSigning")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("releaseSigning")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    // Compose UI & Material 3
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.2")

    // Coroutines & Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Networking & HTML Parsing
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-urlconnection:4.12.0")
    implementation("org.jsoup:jsoup:1.18.1")

    // Image loading (Coil)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // DataStore Preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // WorkManager for background Watchlist & Domain Recovery
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Media3 (Playback, HLS Streaming & Muxer support)
    val media3Version = "1.4.1"
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")
    implementation("androidx.media3:media3-muxer:$media3Version")
    implementation("androidx.media3:media3-extractor:$media3Version")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
