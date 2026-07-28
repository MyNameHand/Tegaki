import mihon.buildlogic.Config
import mihon.buildlogic.getBuildTime
import mihon.buildlogic.getCommitCount
import mihon.buildlogic.getGitSha
import java.util.Base64
import java.util.Properties

plugins {
    id("mihon.android.application")
    id("mihon.android.application.compose")
    id("com.github.zellius.shortcut-helper")
    kotlin("plugin.parcelize")
    kotlin("plugin.serialization")
    alias(libs.plugins.aboutLibraries)
    id("com.github.ben-manes.versions")
}

if (Config.includeTelemetry) {
    pluginManager.apply {
        apply(libs.plugins.google.services.get().pluginId)
        apply(libs.plugins.firebase.crashlytics.get().pluginId)
    }
}

shortcutHelper.setFilePath("./shortcuts.xml")

// KMK -->
// Tegaki release signing, following the pattern Google documents (and Mihon uses): credentials in
// a gitignored keystore.properties locally, environment variables on CI, and signing performed by
// Gradle during the build rather than by a post-build step.
//
// Falls back to the pinned debug keystore, then to the AGP default, so a checkout with neither
// still builds — it just produces differently-signed APKs that cannot update a real install.
val releaseKeystoreProperties = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use(::load) } }

// CI supplies the keystore as base64 so no key file is ever written to the repo checkout.
val releaseKeystoreFile = System.getenv("RELEASE_KEYSTORE_BASE64")
    ?.takeIf(String::isNotBlank)
    ?.let { encoded ->
        layout.buildDirectory.get().asFile.resolve("tegaki-release.keystore").apply {
            parentFile.mkdirs()
            writeBytes(Base64.getDecoder().decode(encoded.trim()))
        }
    }
    ?: (releaseKeystoreProperties?.getProperty("storeFile") ?: "release.keystore")
        .let(rootProject::file)
        .takeIf { it.exists() }

val releaseStorePassword: String? = releaseKeystoreProperties?.getProperty("storePassword")
    ?: System.getenv("RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias: String = releaseKeystoreProperties?.getProperty("keyAlias")
    ?: System.getenv("RELEASE_KEY_ALIAS") ?: "tegaki"
val releaseKeyPassword: String? = releaseKeystoreProperties?.getProperty("keyPassword")
    ?: System.getenv("RELEASE_KEY_PASSWORD") ?: releaseStorePassword

val hasReleaseSigning = releaseKeystoreFile != null && releaseStorePassword != null

// A pinned debug keystore (provided by CI) so preview builds keep a stable
// signature and install as in-place updates. Absent locally -> AGP default.
val pinnedDebugKeystore = rootProject.file("debug.keystore")
// KMK <--

android {
    namespace = "eu.kanade.tachiyomi"

    defaultConfig {
        applicationId = "app.tegaki"

        // Tegaki release discipline: for each new release, bump BOTH versionCode and
        // versionName, then publish a GitHub release tagged "v<versionName>" (must have
        // >= 3 numeric parts, e.g. v1.13.7). The in-app updater compares the installed
        // versionName against the newest release tag on MyNameHand/Tegaki.
        // Tegaki versioning tracks the merged Komikku stable base (1.14.1) + fork bump.
        versionCode = 84
        versionName = "1.14.4"

        buildConfigField("String", "COMMIT_COUNT", "\"${getCommitCount()}\"")
        buildConfigField("String", "COMMIT_SHA", "\"${getGitSha()}\"")
        buildConfigField("String", "BUILD_TIME", "\"${getBuildTime(useLastCommitTime = false)}\"")
        buildConfigField("boolean", "TELEMETRY_INCLUDED", "${Config.includeTelemetry}")
        buildConfigField("boolean", "UPDATER_ENABLED", "${Config.enableUpdater}")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // KMK -->
    signingConfigs {
        getByName("debug") {
            if (pinnedDebugKeystore.exists()) {
                storeFile = pinnedDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        // Upstream Komikku signs release/preview APKs in CI and has no Gradle signing config at
        // all. Tegaki builds and publishes locally, so the real key is wired in here instead.
        if (hasReleaseSigning) {
            create("tegakiRelease") {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    // KMK <--

    buildTypes {
        val debug by getting {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-${getCommitCount()}"
            isPseudoLocalesEnabled = true
        }
        // KMK --> Tegaki's STABLE channel: app.tegaki, purple icon from src/main/res.
        val release by getting {
            isMinifyEnabled = Config.enableCodeShrink
            isShrinkResources = Config.enableCodeShrink

            proguardFiles("proguard-android-optimize.txt", "proguard-rules.pro")

            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("tegakiRelease")
            } else {
                signingConfigs.getByName("debug")
            }

            buildConfigField("String", "BUILD_TIME", "\"${getBuildTime(useLastCommitTime = true)}\"")
        }
        // KMK <--

        val commonMatchingFallbacks = listOf(release.name)

        create("releaseTest") {
            initWith(release)

            applicationIdSuffix = ".rt"
            isMinifyEnabled = false
            isShrinkResources = false

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }
        create("foss") {
            initWith(release)

            applicationIdSuffix = ".foss"

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }
        // KMK --> Tegaki's TEST channel: app.tegaki.test, teal icon from src/beta/res, so a soak
        // build installs alongside stable instead of replacing it.
        //
        // Both channels are variants of the same commit, as upstream Mihon and Komikku do it.
        // Tegaki previously used `preview` for stable and carried the test channel's identity on
        // a separate branch, which silently shipped stale code whenever that branch fell behind.
        create("preview") {
            initWith(release)

            applicationIdSuffix = ".test"

            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("tegakiRelease")
            } else {
                signingConfigs.getByName("debug")
            }

            matchingFallbacks.addAll(commonMatchingFallbacks)

            buildConfigField("String", "BUILD_TIME", "\"${getBuildTime(useLastCommitTime = false)}\"")
        }
        // KMK <--
        create("benchmark") {
            initWith(release)

            isDebuggable = false
            isProfileable = true
            versionNameSuffix = "${debug.versionNameSuffix}-benchmark"
            applicationIdSuffix = ".benchmark"

            signingConfig = debug.signingConfig

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }
    }

    sourceSets {
        getByName("preview").res.srcDirs("src/beta/res")
        getByName("benchmark").res.srcDirs("src/debug/res")
    }

    splits {
        abi {
            isEnable = true
            isUniversalApk = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    packaging {
        jniLibs {
            keepDebugSymbols += listOf(
                "libandroidx.graphics.path",
                "libarchive-jni",
                "libconscrypt_jni",
                "libimagedecoder",
                "libquickjs",
                "libsqlite3x",
            )
                .map { "**/$it.so" }
        }
        resources {
            excludes += setOf(
                "kotlin-tooling-metadata.json",
                "LICENSE.txt",
                "META-INF/**/*.properties",
                "META-INF/**/LICENSE.txt",
                "META-INF/*.properties",
                "META-INF/*.version",
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/NOTICE",
                "META-INF/README.md",
            )
        }
    }

    dependenciesInfo {
        includeInApk = Config.includeDependencyInfo
        includeInBundle = Config.includeDependencyInfo
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = true

        // Disable some unused things
        renderScript = false
        shaders = false
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=coil3.annotation.ExperimentalCoilApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=kotlinx.coroutines.InternalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
            "-Xannotation-default-target=param-property",
        )
    }
}

dependencies {
    implementation(projects.i18n)
    // KMK -->
    implementation(projects.i18nKmk)
    // KMK <--
    // SY -->
    implementation(projects.i18nSy)
    // SY <--
    implementation(projects.core.archive)
    implementation(projects.core.common)
    implementation(projects.coreMetadata)
    implementation(projects.sourceApi)
    implementation(projects.sourceLocal)
    implementation(projects.data)
    implementation(projects.domain)
    implementation(projects.presentationCore)
    implementation(projects.presentationWidget)
    implementation(projects.telemetry)

    // Compose
    implementation(compose.activity)
    implementation(compose.foundation)
    implementation(compose.material3.core)
    implementation(compose.material.icons)
    implementation(compose.animation)
    implementation(compose.animation.graphics)
    debugImplementation(compose.ui.tooling)
    implementation(compose.ui.tooling.preview)
    implementation(compose.ui.util)

    implementation(androidx.interpolator)

    implementation(androidx.paging.runtime)
    implementation(androidx.paging.compose)

    implementation(libs.bundles.sqlite)
    // SY -->
    implementation(sylibs.sqlcipher)
    // SY <--

    implementation(kotlinx.reflect)
    implementation(kotlinx.immutables)

    implementation(platform(kotlinx.coroutines.bom))
    implementation(kotlinx.bundles.coroutines)

    // AndroidX libraries
    implementation(androidx.annotation)
    implementation(androidx.appcompat)
    implementation(androidx.biometricktx)
    implementation(androidx.constraintlayout)
    implementation(androidx.corektx)
    implementation(androidx.splashscreen)
    implementation(androidx.recyclerview)
    implementation(androidx.viewpager)
    implementation(androidx.profileinstaller)

    implementation(androidx.bundles.lifecycle)

    // Job scheduling
    implementation(androidx.workmanager)

    // RxJava
    implementation(libs.rxjava)

    // Networking
    implementation(libs.bundles.okhttp)
    implementation(libs.okio)
    implementation(libs.conscrypt.android) // TLS 1.3 support for Android < 10

    // Data serialization (JSON, protobuf, xml)
    implementation(kotlinx.bundles.serialization)

    // HTML parser
    implementation(libs.jsoup)

    // Disk
    implementation(libs.disklrucache)
    implementation(libs.unifile)

    // Preferences
    implementation(libs.preferencektx)

    // Dependency injection
    implementation(libs.injekt)

    // Image loading
    implementation(platform(libs.coil.bom))
    implementation(libs.bundles.coil)
    implementation(libs.subsamplingscaleimageview) {
        exclude(module = "image-decoder")
    }
    implementation(libs.image.decoder)

    // UI libraries
    implementation(libs.material)
    implementation(libs.flexible.adapter.core)
    implementation(libs.photoview)
    implementation(libs.directionalviewpager) {
        exclude(group = "androidx.viewpager", module = "viewpager")
    }
    implementation(libs.richeditor.compose)
    implementation(libs.aboutLibraries.compose)
    implementation(libs.bundles.voyager)
    implementation(libs.compose.materialmotion)
    implementation(libs.swipe)
    implementation(libs.compose.webview)
    implementation(libs.compose.grid)
    implementation(libs.reorderable)
    implementation(libs.bundles.markdown)
    implementation(libs.materialKolor)

    // KMK -->
    implementation(libs.palette.ktx)
    implementation(libs.haze)
    implementation(compose.colorpicker)
    implementation(projects.flagkit)
    // KMK <--

    // Logging
    implementation(libs.timber)
    implementation(libs.logcat)

    // Shizuku
    implementation(libs.bundles.shizuku)

    // String similarity
    implementation(libs.stringSimilarity)

    // Tests
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    // For detecting memory leaks; see https://square.github.io/leakcanary/
    // debugImplementation(libs.leakcanary.android)
    implementation(libs.leakcanary.plumber)

    testImplementation(kotlinx.coroutines.test)

    // SY -->
    // Better logging (EH)
    implementation(sylibs.xlog)

    // RatingBar (SY)
    implementation(sylibs.ratingbar)
    implementation(sylibs.composeRatingbar)

    // Google drive
    implementation(sylibs.google.api.services.drive)

    // ZXing Android Embedded
    implementation(sylibs.zxing.android.embedded)
}

androidComponents {
    onVariants(selector().withFlavor("default" to "standard")) {
        // Only excluding in standard flavor because this breaks
        // Layout Inspector's Compose tree
        it.packaging.resources.excludes.add("META-INF/*.version")
    }
}

buildscript {
    dependencies {
        classpath(kotlinx.gradle)
    }
}
