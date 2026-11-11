plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cz.titulky.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "cz.titulky.tv"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "NG 0.1"
        // jen procesory, které mají televize (ARM) – aplikace je tak o třetinu menší
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // Překlad přímo v televizi – zdarma, bez limitu (Google ML Kit)
    implementation("com.google.mlkit:translate:17.0.3")
    // Čtení textu z obrazu – zdarma, v televizi, bez stahování (Google ML Kit)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Rozpoznání jazyka – překládáme jen anglický text (zdarma, v televizi)
    implementation("com.google.mlkit:language-id:17.0.6")
    // QR kód pro přihlášení k PSN přes telefon
    implementation("com.google.zxing:core:3.5.3")
}
