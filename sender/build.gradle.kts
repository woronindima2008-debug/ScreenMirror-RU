plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "ru.screenmirror.sender"
    compileSdk = 35
    defaultConfig { applicationId = "ru.screenmirror.sender"; minSdk = 26; targetSdk = 35; versionCode = 8; versionName = "0.5.3" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")
}
