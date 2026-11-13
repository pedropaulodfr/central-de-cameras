plugins {
    // AGP 9 já traz suporte a Kotlin embutido: não se aplica mais o
    // plugin 'org.jetbrains.kotlin.android' (ver kotl.in/gradle/agp-built-in-kotlin).
    // O plugin do compilador Compose continua obrigatório.
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "br.com.pedro.centralcameras"
    // AndroidX recente exige compilar contra a API 37.
    compileSdk = 37

    defaultConfig {
        applicationId = "br.com.pedro.centralcameras"
        // Galaxy J4 Core roda Android 8.1 (API 27).
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.0"

        // As arquiteturas ficam definidas em `splits`, abaixo. Não usar
        // ndk.abiFilters junto com splits: o Gradle recusa a combinação.
    }

    splits {
        abi {
            isEnable = true
            reset()
            // x86_64 é só para rodar no emulador durante o desenvolvimento.
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")

    // Player HLS (modo remoto, via gateway no PC)
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")

    // Player RTSP do modo direto.
    // Usamos o libVLC (o mesmo player do app de PC) porque o RTSP do Media3
    // tem um NullPointerException fatal quando o SDP do firmware é incompleto
    // (caso das Yoosee que anunciam H265 sem sprop-vps) — ele derruba o app.
    implementation("org.videolan.android:libvlc-all:3.6.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
