import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val unsignedBuild = providers.gradleProperty("unsignedBuild").map { it.toBoolean() }.getOrElse(true)
// Opt-in identity for a side-by-side recovery test. Never reuse the original signing config.
val recoveryTestBuild = providers.gradleProperty("recoveryTestBuild").map { it.toBoolean() }.getOrElse(false)
require(!recoveryTestBuild || unsignedBuild) {
    "Recovery test builds require -PunsignedBuild=true; sign the separate APK outside Gradle."
}

android {
    namespace = "com.jarvys.agent"
    compileSdk = 36

    androidResources {
        generateLocaleConfig = true
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("play") { dimension = "distribution" }
        create("full") { dimension = "distribution" }
    }

    defaultConfig {
        applicationId = if (recoveryTestBuild) "com.jarvys.agent.recoverytest" else "com.jarvys.agent"
        minSdk = 24
        targetSdk = 35
        versionCode = 65
        versionName = "1.2.58-FACTORY-PREVIEW" + if (recoveryTestBuild) "-test" else ""
        manifestPlaceholders["jarvysApplicationLabel"] = if (recoveryTestBuild) "Jarvys Prueba" else "@string/app_name"
        manifestPlaceholders["jarvysNotificationListenerLabel"] =
            if (recoveryTestBuild) "Jarvys Prueba: notificaciones" else "@string/notification_listener_label"
    }

    buildTypes {
        debug {
            // Development APKs are always unsigned. The reviewed final APK is signed externally
            // with the existing approved release identity; never synthesize a replacement key.
            signingConfig = null
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":factory-contract"))
    implementation(project(":factory-runtime-core"))
    implementation("androidx.webkit:webkit:1.14.0")
    // Pinned to the APK factory signing/verification contract; upgrade only with its dedicated compatibility validation.
    //noinspection GradleDependency
    implementation("com.android.tools.build:apksig:8.13.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    add("fullImplementation", "com.google.android.gms:play-services-auth:22.0.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.browser:browser:1.8.0")
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    // UX40 manual pilot: retain the reviewed app Compose stack; Rive must not replace its BOM.
    implementation("app.rive:rive-android:11.14.1") {
        exclude(group = "androidx.compose", module = "compose-bom")
    }
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.mikepenz:multiplatform-markdown-renderer:0.37.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.37.0")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.work:work-testing:2.12.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Compile the reusable native runtime once for this Jarvys build. Per-app generation uses
// only this unsigned template and the in-process JVM packager, never Gradle or downloaded tools.
val factoryAssets = layout.buildDirectory.dir("generated/apkFactoryAssets")
val prepareApkFactoryTemplate by tasks.registering {
    dependsOn(":apk-runtime:assembleRelease")
    val template = project(":apk-runtime").layout.buildDirectory.file("outputs/apk/release/apk-runtime-release-unsigned.apk")
    inputs.file(template)
    outputs.dir(factoryAssets)
    doLast {
        val bytes = template.get().asFile.readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val directory = factoryAssets.get().dir("apk_factory").asFile.apply { mkdirs() }
        directory.resolve("template.apk").writeBytes(bytes)
        directory.resolve("template.sha256").writeText(hash + "\n")
    }
}
android.sourceSets.getByName("main").assets.srcDir(factoryAssets)
tasks.configureEach {
    if ((name.startsWith("merge") && name.endsWith("Assets")) || name.startsWith("lintAnalyze") ||
        name.endsWith("LintReportModel") || name.endsWith("LintModel")) {
        // Lint also consumes the generated asset directory; combined test/lint builds require the same producer dependency.
        dependsOn(prepareApkFactoryTemplate)
    }
}
