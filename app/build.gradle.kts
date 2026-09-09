import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

/**
 * Local signing credentials, absent on CI and on a fresh clone.
 *
 * The file is gitignored and points at a keystore stored outside the repository. Android
 * identifies an installed app by applicationId plus signing certificate, so losing this
 * key means the app can never be updated in place again.
 *
 * The React Native build this replaces was signed with the stock Android debug key,
 * because Expo's template wires `release` to `signingConfigs.debug`. That is exactly the
 * accident this block exists to avoid repeating.
 */
val keystoreProperties =
    rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
        Properties().apply { file.inputStream().use { load(it) } }
    }

plugins {
    // AGP 9 applies the Kotlin Android plugin itself; applying it here as well fails.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.showtracker.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.showtracker.app"
        resValue("string", "app_name", "Show Tracker")
        // The React Native build allowed 24. Nothing here needs to run that far back,
        // and 26 is what the sibling habit_tracker targets.
        minSdk = 26
        targetSdk = 37
        // Bumped with every build that goes on the phone. This app is sideloaded, so the
        // version is the only thing that says which build is installed - it sat at 1
        // through every release since the cutover, and telling two of them apart meant
        // reading install timestamps out of `dumpsys`.
        versionCode = 2
        versionName = "0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Only declared when the credentials are present. On CI and on a fresh clone the
        // release build simply comes out unsigned, which is correct: an unsigned artifact
        // is obviously unusable, whereas one silently signed with the debug key looks fine
        // and then cannot be updated by a real release later.
        keystoreProperties?.let { props ->
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // The release install on the phone holds the only live copy of the library:
            // there is no server, and an uninstall erases it. A distinct applicationId
            // lets a debug build be tried beside it, with its own database, rather than
            // replacing it. (This said the React Native build was the one in daily use
            // until 2026-09-09; the cutover happened on 2026-08-15, and the reason the
            // suffix is here has outlived the reason it was added.)
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"

            // A distinct launcher label as well as a distinct id. Without it both builds
            // appear as "Show Tracker" with the same icon, and the only way to tell which
            // is which is to open App info and read the version - which is no help at all
            // when the point of installing both is to compare them.
            resValue("string", "app_name", "Show Tracker (debug)")
        }
        release {
            signingConfig = signingConfigs.findByName("release")

            // Left off for now: an unminified release is one fewer variable if a
            // release-only failure ever appears.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // AGP 9 turns custom resource values off by default. app_name is declared per build
        // type so the debug install is labelled distinctly on the launcher.
        resValues = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        // This is a personal app with no release train, so a warning that never gets
        // triaged is just noise. Fail the build instead.
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        disable +=
            setOf(
                // Version bumps are Dependabot's job, not a build failure's.
                "GradleDependency",
                "NewerVersionAvailable",
                "AndroidGradlePluginVersion",
            )
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// The migration test replays the committed schema JSONs, so they have to be on the
// instrumentation test classpath as assets.
android.sourceSets
    .getByName("androidTest")
    .assets
    .srcDir("$projectDir/schemas")

// AGP 9 dropped the `kotlinOptions` block in favour of the Kotlin plugin's own DSL.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)

    // The periodic season check. Expo's background-task module was WorkManager
    // underneath, so this is the same mechanism without the wrapper.
    implementation(libs.androidx.work.runtime.ktx)

    // Poster loading, with its own memory and disk cache. Shares the app's OkHttp client
    // so there is one connection pool rather than two.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Plain OkHttp rather than Retrofit: this app calls three TMDB endpoints, and a
    // declarative interface plus a converter artifact would be more machinery than the
    // hand-written mapping it replaces.
    implementation(libs.okhttp)

    // Reads the versioned JSON an export produces. Pinned through the BOM so the core and
    // json artifacts cannot drift apart, which surfaces only at runtime.
    implementation(platform(libs.kotlinx.serialization.bom))
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Exercises the TMDB wire mapping and error handling over a real socket, without a
    // network or a live API key.
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // Replays committed schema JSONs so migrations are verified, not assumed.
    androidTestImplementation(libs.androidx.room.testing)
    // Room's MigrationTestHelper parses those JSONs with kotlinx-serialization, and a
    // core/json version mismatch surfaces only at runtime as AbstractMethodError.
    androidTestImplementation(platform(libs.kotlinx.serialization.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}
