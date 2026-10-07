plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Version from git: tag v1.2.3 → "1.2.3"; later commits → "1.2.3-4-gabc1234"; no tag → "0.0.0-abc1234".
// versionCode = number of commits, so every build from main can update the previous one.
fun git(vararg args: String): String? = try {
    providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { null }
} catch (e: Exception) {
    null
}

val gitSha = git("rev-parse", "--short", "HEAD") ?: "unknown"
val gitVersion = git("describe", "--tags", "--match", "v[0-9]*", "--dirty")?.removePrefix("v")
    ?: "0.0.0-$gitSha"
val gitCommitCount = git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1

// Release signing only from the environment (CI secrets); never committed.
fun env(name: String): String? = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
val releaseKeystore = env("SYNSUCK_KEYSTORE")

android {
    namespace = "pl.synsuck"
    // Current AndroidX needs API 37; must match what the Docker image installs.
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "pl.synsuck"
        minSdk = 26
        targetSdk = 36
        versionCode = gitCommitCount
        versionName = gitVersion
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = env("SYNSUCK_KEYSTORE_PASSWORD")
                keyAlias = env("SYNSUCK_KEY_ALIAS")
                keyPassword = env("SYNSUCK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Unsigned when no keystore is configured (e.g. local builds).
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        localeFilters += listOf("en", "pl")
        // Generates locales_config.xml → per-app language in system settings (Android 13+).
        generateLocaleConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":librus-client"))

    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
