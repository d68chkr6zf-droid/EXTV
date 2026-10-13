plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")
}

android {
    namespace = "com.extv.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.extv.player"
        minSdk = 21
        targetSdk = 35
        // Numéro de version = numéro de compilation GitHub Actions (sert au bouton « Mise à jour »).
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.0.$build"
        buildConfigField("String", "UPDATE_URL", "\"https://github.com/d68chkr6zf-droid/EXTV/releases/download/latest/\"")

        // Serveur Xtream proposé par défaut sur l'écran de connexion (modifiable par l'utilisateur).
        buildConfigField("String", "DEFAULT_SERVER", "\"http://718e.dataxtm.com\"")
    }

    // Clé de signature fixe (fournie par les secrets GitHub EXTV_SIGNING_KEY et EXTV_SIGNING_PASSWORD, jamais dans le dépôt) :
    // indispensable pour installer une mise à jour par-dessus la version existante.
    val keystore = System.getenv("EXTV_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("fixed") {
                storeFile = keystore
                storeType = "PKCS12"
                storePassword = System.getenv("EXTV_KEYSTORE_PASSWORD")
                keyAlias = "extv"
                keyPassword = System.getenv("EXTV_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (keystore != null) signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.animation:animation")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Moteur de lecture VLC (tous codecs, HLS, MPEG-TS, sous-titres, pistes audio)
    implementation("org.videolan.android:libvlc-all:3.6.5")

    // Lecteur natif Android (ExoPlayer / Media3), au choix dans les paramètres
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    testImplementation("junit:junit:4.13.2")
}
