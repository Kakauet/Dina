import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing key: a properties file outside the repository (storeFile, storePassword, keyAlias,
// keyPassword; storeFile relative to the file). `.\dev.ps1 keystore` creates ~/.dina/release.properties;
// DINA_SIGNING points elsewhere. Without it the release APK stays unsigned and `.\dev.ps1 apk` stops:
// the debug key differs on every PC, so APKs signed with it could not update each other.
val signingFile = providers.environmentVariable("DINA_SIGNING")
    .orElse(providers.systemProperty("user.home").map { "$it/.dina/release.properties" })
    .map { file(it) }.get()
val signingProperties = providers.fileContents(layout.projectDirectory.file(signingFile.absolutePath)).asText.orNull
    ?.let { text -> Properties().apply { load(text.reader()) } }

android {
    namespace = "com.kakauet.dina"
    compileSdk = 36
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "com.kakauet.dina"
        minSdk = 34
        targetSdk = 36
        versionCode = 16
        versionName = "2.4"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3", "-fvisibility=hidden")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DBUILD_SHARED_LIBS=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_CURL=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_BACKEND_DL=OFF",
                    "-DGGML_CPU_ALL_VARIANTS=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DGGML_CPU_KLEIDIAI=ON"
                )
            }
        }
    }

    // Two editions from the same code: "full" (Dina 4.5 1.2B, the S24 Ultra and alike) and "lite" (Dina 4.5 350M, modest phones).
    // They install side by side (applicationId .lite) and share the release key. BuildConfig.LITE picks the brain (BrainRegistry).
    flavorDimensions += "edition"
    productFlavors {
        create("full") {
            dimension = "edition"
            buildConfigField("boolean", "LITE", "false")
            resValue("string", "app_name", "Dina")
        }
        create("lite") {
            dimension = "edition"
            applicationIdSuffix = ".lite"
            buildConfigField("boolean", "LITE", "true")
            resValue("string", "app_name", "Dina Lite")
        }
    }

    signingConfigs {
        if (signingProperties != null) create("release") {
            fun required(key: String): String = signingProperties.getProperty(key)?.takeIf { it.isNotBlank() }
                ?: error("$signingFile: falta $key")
            storeFile = signingFile.parentFile.resolve(required("storeFile"))
            storePassword = required("storePassword")
            keyAlias = required("keyAlias")
            keyPassword = required("keyPassword")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Models (~900 MB full, ~550 MB lite) are packed only where needed: always in release, in debug only with
    // -Pdina.bundleModels=true. Development APKs load them from storage (.\dev.ps1 push-models [lite]).
    // android/model-assets/<edition>/ is staged by scripts/models/prepare-models.ps1 -Variant <edition>.
    for (edition in listOf("full", "lite")) {
        sourceSets.maybeCreate("${edition}Release").assets.srcDir(rootProject.file("model-assets/$edition"))
        if (providers.gradleProperty("dina.bundleModels").orNull.toBoolean()) {
            sourceSets.maybeCreate("${edition}Debug").assets.srcDir(rootProject.file("model-assets/$edition"))
        }
    }

    androidResources {
        noCompress += listOf("gguf", "ort", "onnx", "bin", "json")
    }

    packaging {
        jniLibs.useLegacyPackaging = false
        // Moonshine ships ORT 1.23 with its custom STT/TTS operators. Keep that runtime;
        // onnxruntime-android contributes the Java API used by the Dina wake-word graphs.
        jniLibs.pickFirsts += "**/libonnxruntime.so"
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Robolectric screenshot tests read fonts and other resources.
        unitTests.isIncludeAndroidResources = true
    }
}

// Screenshot tests (ui/screenshots) render PNGs with Robolectric + Roborazzi. They are slow, so
// `testDebugUnitTest` skips them unless -Pdina.screenshots=<output dir> is given (.\dev.ps1 screenshots).
// -Pdina.icons=<res dir> (.\dev.ps1 icons) only regenerates the launcher icon PNGs from the character code.
// -Pdina.eval=<brain>,<set>[,key=value] (.\dev.ps1 eval) runs only the evaluator, with the app's JNI library
// built for the PC (android/build/llm-bench/dina_native.dll). Normal runs skip it.
// -Pdina.data=<step>,batch=<name>[,key=value] (.\dev.ps1 data) runs one Kotlin step of the data pipeline.
// -Pdina.latency=[key=value,…] (.\dev.ps1 latency) runs only the PC latency bench (llama.cpp DLL + Supertonic).
val screenshotDir = providers.gradleProperty("dina.screenshots")
val iconsDir = providers.gradleProperty("dina.icons")
val evalArgs = providers.gradleProperty("dina.eval")
val dataArgs = providers.gradleProperty("dina.data")
val latencyArgs = providers.gradleProperty("dina.latency")
tasks.withType<Test>().configureEach {
    val dir = screenshotDir.orNull
    val icons = iconsDir.orNull
    val eval = evalArgs.orNull
    val data = dataArgs.orNull
    val latency = latencyArgs.orNull
    if (latency != null) {
        filter.includeTestsMatching("com.kakauet.dina.eval.LatencyRunTest")
        systemProperty("dina.latency", latency)
        systemProperty("dina.root", rootProject.projectDir.parentFile.absolutePath)
        systemProperty("java.library.path", rootProject.file("build/llm-bench").absolutePath)
        systemProperty("user.timezone", "Europe/Madrid")
        outputs.upToDateWhen { false }
    } else if (data != null) {
        filter.includeTestsMatching("com.kakauet.dina.data.DataRunTest")
        systemProperty("dina.data", data)
        systemProperty("dina.root", rootProject.projectDir.parentFile.absolutePath)
        systemProperty("user.timezone", "Europe/Madrid")
        outputs.upToDateWhen { false }
    } else if (eval != null) {
        filter.includeTestsMatching("com.kakauet.dina.eval.EvalRunTest")
        systemProperty("dina.eval", eval)
        systemProperty("dina.root", rootProject.projectDir.parentFile.absolutePath)
        systemProperty("java.library.path", rootProject.file("build/llm-bench").absolutePath)
        systemProperty("user.timezone", "Europe/Madrid")
        outputs.upToDateWhen { false }
    } else if (icons != null) {
        filter.includeTestsMatching("com.kakauet.dina.ui.screenshots.IconRenderTest.layers")
        systemProperty("dina.icons.res", icons)
        outputs.upToDateWhen { false }
    } else if (dir == null) {
        exclude("**/screenshots/**")
        exclude("**/eval/EvalRunTest*")
        exclude("**/data/DataRunTest*")
        exclude("**/eval/LatencyRunTest*")
    } else {
        filter.includeTestsMatching("com.kakauet.dina.ui.screenshots.*")
        systemProperty("roborazzi.test.record", "true")
        systemProperty("dina.screenshots.dir", dir)
        outputs.upToDateWhen { false }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01") // Compose 1.12+ needs compileSdk 37 + AGP 9.1
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("ai.moonshine:moonshine-voice:0.1.0")
    // Moonshine 0.1.0 embeds its custom ONNX Runtime core at ABI 1.23.0.
    // The Java JNI must match that exact ABI or OrtEnvironment fails during static init.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.0")
    testImplementation("junit:junit:4.13.2")
    // Desktop ONNX Runtime (same version) so `.\dev.ps1 latency` runs Supertonic on the PC.
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.23.0")
    // Real org.json for JVM tests (android.jar only ships stubs).
    testImplementation("org.json:json:20250517")
    // Screenshot tests: Robolectric native graphics + Roborazzi. SDK 35+ sandboxes need JDK 21, so they run on SDK 34.
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.76.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
