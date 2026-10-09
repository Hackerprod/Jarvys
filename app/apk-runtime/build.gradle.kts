plugins { id("com.android.application") }

android {
    namespace = "com.jarvys.factory.runtime"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.jarvys.factory.template"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "FACTORY_VERSION"
    }
    buildTypes {
        debug { signingConfig = null }
        release { isMinifyEnabled = false }
    }
    buildFeatures { buildConfig = false }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

// Apply to both the direct Core dependency and WebKit's transitive path. Optional profile
// installation must not add a provider/receiver or template-specific authority to generated apps.
configurations.configureEach {
    exclude(group = "androidx.profileinstaller", module = "profileinstaller")
}

dependencies {
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.webkit:webkit:1.14.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.16")
}
