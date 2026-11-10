plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "ru.screenmirror.receiver"
    compileSdk = 35
    defaultConfig { applicationId = "ru.screenmirror.receiver"; minSdk = 26; targetSdk = 35; versionCode = 2; versionName = "0.2.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("io.getstream:stream-webrtc-android:1.3.10")
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
    implementation("com.google.zxing:core:3.5.3")
}
