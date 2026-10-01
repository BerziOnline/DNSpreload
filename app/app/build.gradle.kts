import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi") version "1.40.1"
}

val keystoreProps = Properties().apply { val f = rootProject.file("keystore.properties"); if (f.exists()) f.inputStream().use { this.load(it) } }

// Optional, commit-able private defaults. Public exports may omit this file.
val appProps = Properties().apply {
    val f = rootProject.file("app.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val defaultServerUrl = providers.gradleProperty("dnspreload.defaultServerUrl").orNull
    ?: appProps.getProperty("dnspreload.defaultServerUrl", "")
// Escape a Java string literal, including control characters in project properties.
val defaultServerUrlLiteral = "\"" + defaultServerUrl.map { ch ->
    when (ch) {
        '\\' -> "\\\\"
        '"' -> "\\\""
        '\n' -> "\\n"
        '\r' -> "\\r"
        '\t' -> "\\t"
        else -> ch.toString()
    }
}.joinToString("") + "\""

android {
    namespace = "io.github.berzionline.dnspreload"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.berzionline.dnspreload"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "2.0.2"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "DEFAULT_SERVER_URL", defaultServerUrlLiteral)
    }
    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.05.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
    // README screenshots (ReadmeShots.kt): Robolectric + Roborazzi, no emulator needed
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.40.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.40.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
