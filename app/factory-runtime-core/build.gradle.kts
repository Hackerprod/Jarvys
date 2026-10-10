plugins { id("com.android.library") }
android {
    namespace = "com.jarvys.factory.runtime.core"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    buildFeatures { buildConfig = false }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
// This offline runtime does not use optional profile installation. Keep its provider/receiver
// out of generated applications and use the same exclusion for this library's test graph.
configurations.configureEach {
    exclude(group = "androidx.profileinstaller", module = "profileinstaller")
}
dependencies {
    api(project(":factory-contract"))
    api("androidx.core:core:1.15.0")
    implementation("androidx.webkit:webkit:1.14.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.16")
}
