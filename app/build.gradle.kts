plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.screentranslator.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.screentranslator.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.4.0"
    }

    // 저장소에 들어 있는 고정 디버그 키로 서명해서, 어디서 빌드해도 기존 앱 위에 업데이트 설치가 되게 한다.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 글자 인식 모델은 앱 안에 포함되어 설치 직후부터 오프라인으로 동작한다.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // 번역 모델은 최초 1회 다운로드 후 오프라인으로 동작한다.
    implementation("com.google.mlkit:translate:17.0.3")
}
