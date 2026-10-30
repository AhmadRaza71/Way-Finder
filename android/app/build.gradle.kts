import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val props = Properties().apply { val f = rootProject.file("local.properties"); if (f.exists()) f.inputStream().use { load(it) } }
val mapsKey: String = System.getenv("MAPS_API_KEY") ?: props.getProperty("MAPS_API_KEY") ?: ""

android {
    namespace = "com.example.wayfinder"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.wayfinder"; minSdk = 26; targetSdk = 34; versionCode = 2; versionName = "2.0"
        manifestPlaceholders["MAPS_API_KEY"] = mapsKey
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsKey\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    kotlinOptions { jvmTarget = "1.8" }
}
dependencies {
    implementation("androidx.car.app:app:1.4.0")
    implementation("com.google.android.gms:play-services-maps:19.0.0")
    implementation("com.google.maps.android:android-maps-utils:3.8.2")
    implementation("androidx.core:core-ktx:1.13.1")
}
