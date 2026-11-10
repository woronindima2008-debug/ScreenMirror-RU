plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "ru.screenmirror.sender"
    compileSdk = 35
    defaultConfig { applicationId = "ru.screenmirror.sender"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Базовая WebRTC-библиотека; пока подключена для проверки совместимости сборки.
    implementation("io.getstream:stream-webrtc-android:1.3.10")
}
