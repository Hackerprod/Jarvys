import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = 24
        targetSdk = 35
        versionCode = 98
        versionName = "1.2.91-BOT-CONTINUITY" + if (recoveryTestBuild) "-test" else ""
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
        unitTests.all {
            it.systemProperty("room.schemaDir", "$projectDir/schemas")
            // Robolectric runs every test on the main looper; production keeps the chat-database main-thread guard on.
            it.systemProperty("jarvys.unitTests.mainThreadGuardOff", "true")
        }
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

// Host unit tests use the JVM native loader; Android production/instrumentation retain the AAR.
// Never allow duplicate BundledSQLiteDriver/NativeLibraryLoader classes to depend on classpath order.
configurations.matching {
    it.name.endsWith("UnitTestCompileClasspath") || it.name.endsWith("UnitTestRuntimeClasspath")
}.configureEach {
    exclude(group = "androidx.sqlite", module = "sqlite-bundled-android")
}

dependencies {
    implementation(project(":factory-contract"))
    implementation(project(":factory-runtime-core"))
    // Dedicated cookie/auth-free animation transport; Kotlin remains 2.2.20. Apache-2.0.
    implementation("com.squareup.okhttp3:okhttp:5.2.3")
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
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("io.github.huarangmeng:latex-renderer-android:1.5.6-kt2.1.0")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    // One bundled engine serves Room and the provider's independent read-only pool.
    // Framework support remains for isolated SupportSQLite fixtures, not production file handles.
    implementation("androidx.sqlite:sqlite-framework:2.6.1")
    implementation("androidx.sqlite:sqlite-bundled:2.6.1")
    ksp("androidx.room:room-compiler:2.7.2")
    testImplementation("androidx.room:room-testing:2.7.2")
    testImplementation("androidx.sqlite:sqlite-bundled-jvm:2.6.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.mozilla:rhino:1.7.15")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.work:work-testing:2.12.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
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
        name.startsWith("lintVitalAnalyze") || name.endsWith("LintVitalReportModel") ||
        name.endsWith("LintReportModel") || name.endsWith("LintModel")) {
        // Lint also consumes the generated asset directory; combined test/lint builds require the same producer dependency.
        dependsOn(prepareApkFactoryTemplate)
    }
}
