plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bmwflow.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bmwflow.launcher"
        minSdk = 27
        targetSdk = 34
        // Номер версии = номер сборки GitHub Actions: каждая новая сборка «новее» предыдущей.
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = build
        versionName = "1.0.$build"
    }

    // Ключ подписи расшифровывается в CI из app/release.keystore.enc (пароль — секрет KEYSTORE_PASSWORD).
    val keystore = file("release.keystore")
    signingConfigs {
        create("release") {
            storeFile = keystore
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = "bmwflow"
            keyPassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystore.exists()) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
