import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keyProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "eu.jm.magneticplayer"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "eu.jm.magneticplayer"
        minSdk = 26
        targetSdk = 36
        versionCode = (project.findProperty("vCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("vName") as String?) ?: "1.0-dev"
    }
    signingConfigs {
        create("release") {
            if (keyProps.isNotEmpty()) {
                storeFile = rootProject.file(keyProps.getProperty("storeFile"))
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (keyProps.isNotEmpty()) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.media3:media3-datasource:1.8.0")
    implementation("androidx.media3:media3-database:1.8.0")
    implementation("com.google.zxing:core:3.5.3") // QR parowania
    // Chromecast (Google Cast, odbiornik domyślny Google'a) i wykrywanie urządzeń w sieci
    implementation("com.google.android.gms:play-services-cast-framework:22.1.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("androidx.appcompat:appcompat:1.7.1") // ekran sterowania Cast SDK dziedziczy po AppCompatActivity
    // FFmpeg: dźwięk AC3/E-AC3/DTS/TrueHD, którego telefony nie dekodują same (build Jellyfina)
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.8.0+1")
    // przesyłanie pobranych filmów na serwer w tle (Uploads.kt): wyzwalacz MediaStore, czekanie na Wi-Fi
    implementation("androidx.work:work-runtime-ktx:2.10.1")
}
