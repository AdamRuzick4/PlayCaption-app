import java.security.MessageDigest

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
        versionCode = 33
        versionName = "0.21"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // jen procesory, které mají televize (ARM) – aplikace je tak o třetinu menší
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    // kontrola "lint" při podepsaném sestavení padá na novém Javě (25) → vypnout, aplikaci neovlivní
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    testImplementation("org.json:json:20240303")
    implementation("com.github.mwiede:jsch:2.28.8")
    implementation("androidx.core:core-ktx:1.13.1")
    // Překlad přímo v televizi – zdarma, bez limitu (Google ML Kit)
    implementation("com.google.mlkit:translate:17.0.3")
    // Čtení textu z obrazu – zdarma, v televizi, bez stahování (Google ML Kit)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Rozpoznání jazyka – překládáme jen anglický text (zdarma, v televizi)
    implementation("com.google.mlkit:language-id:17.0.6")
    // QR kód pro přihlášení k PSN přes telefon
    implementation("com.google.zxing:core:3.5.3")
    // Xbox: příjem obrazu z konzole (WebRTC, Google libwebrtc v balíčku od Stream – Apache 2.0)
    implementation("io.getstream:stream-webrtc-android:1.3.7")
}

// Dva malé assets vzniknou ze stejných zdrojů jako samostatný LG Release.
val lgAssets = layout.buildDirectory.dir("generated/lgReceiverAssets")
val prepareLgReceiver = tasks.register<Exec>("prepareLgReceiver") {
    inputs.files(fileTree(rootProject.file("webos")) { include("app/**", "service/**", "*.cjs", "*.py", "*.md") })
    inputs.files(rootProject.file("LICENSE"), rootProject.file("BACKLOG.md"))
    outputs.dir(lgAssets)
    outputs.dir(rootProject.file("webos/dist"))
    workingDir(rootProject.projectDir)
    commandLine("python3", "webos/build.py")
    doLast {
        val dist = rootProject.file("webos/dist")
        val manifestFile = dist.resolve("receiver-manifest.json")
        val manifest = groovy.json.JsonSlurper().parse(manifestFile) as Map<*, *>
        val name = manifest["file"] as String
        require(name.matches(Regex("cz\\.playcaption\\.overlaytest_[0-9]+\\.[0-9]+\\.[0-9]+_all\\.ipk")))
        val data = dist.resolve(name).readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        require(manifest["id"] == "cz.playcaption.overlaytest" && manifest["platform"] == "webos")
        require((manifest["size"] as Number).toLong() == data.size.toLong() && manifest["sha256"] == hash)
        val target = lgAssets.get().dir("lg").asFile.apply { mkdirs() }
        target.resolve("receiver.ipk").writeBytes(data)
        manifestFile.copyTo(target.resolve("receiver-manifest.json"), overwrite = true)
    }
}
android.sourceSets.getByName("main").assets.srcDir(lgAssets)
tasks.named("preBuild") { dependsOn(prepareLgReceiver) }

// Samsung přijímač: nepodepsaný WGT ze stejných zdrojů jako samostatné vydání.
// Podpis vzniká až při instalaci, protože certifikát Samsung je vázaný na DUID konkrétní TV.
val samsungAssets = layout.buildDirectory.dir("generated/samsungReceiverAssets")
val prepareSamsungReceiver = tasks.register<Exec>("prepareSamsungReceiver") {
    inputs.files(fileTree(rootProject.file("tizen")) { include("app/**", "test/*.cjs", "*.py") })
    inputs.files(rootProject.file("webos/service/protocol.js"), rootProject.file("webos/app/receiver-ui.js"))
    outputs.dir(samsungAssets)
    outputs.dir(rootProject.file("tizen/dist"))
    workingDir(rootProject.projectDir)
    commandLine("python3", "tizen/build.py")
    doLast {
        val dist = rootProject.file("tizen/dist")
        val manifestFile = dist.resolve("receiver-manifest.json")
        val manifest = groovy.json.JsonSlurper().parse(manifestFile) as Map<*, *>
        val name = manifest["file"] as String
        require(name.matches(Regex("PlayCaption-Samsung-[0-9]+\\.[0-9]+\\.[0-9]+-nepodpisany\\.wgt")))
        val data = dist.resolve(name).readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        require(manifest["id"] == "PCaption01.PlayCaptionTest" && manifest["platform"] == "tizen")
        require((manifest["size"] as Number).toLong() == data.size.toLong() && manifest["sha256"] == hash)
        val target = samsungAssets.get().dir("samsung").asFile.apply { mkdirs() }
        target.resolve("receiver.wgt").writeBytes(data)
        manifestFile.copyTo(target.resolve("receiver-manifest.json"), overwrite = true)
    }
}
android.sourceSets.getByName("main").assets.srcDir(samsungAssets)
tasks.named("preBuild") { dependsOn(prepareSamsungReceiver) }
