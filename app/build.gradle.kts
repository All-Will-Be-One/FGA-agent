import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    id("kotlin-parcelize")
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

val forkVersion = Properties().apply {
    rootProject.file("fga-version.properties").inputStream().use { load(it) }
}
val upstreamVersion = forkVersion.getProperty("upstream.tag").removePrefix("v").toInt()
val forkPatch = forkVersion.getProperty("patch")
require(Regex("0x[0-9a-f]{4}").matches(forkPatch)) { "Invalid native patch version: $forkPatch" }
val forkVersionCode = forkVersion.getProperty("versionCode").toInt()
require(forkVersionCode.toLong() == upstreamVersion.toLong() * 65536 + forkPatch.removePrefix("0x").toInt(16)) {
    "versionCode must match the upstream version and native patch in fga-version.properties"
}
require(forkVersionCode in 1..2100000000) { "Native versionCode is outside Android's supported release range" }

android {
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    defaultConfig {
        // Distinct installation and storage: never overwrite the official app.
        applicationId = "io.github.fate_grand_automata.automation"
        minSdk = 24
        targetSdk = 36
        versionCode = forkVersionCode
        versionName = "$upstreamVersion-$forkPatch"
        buildConfigField("String", "AUTOMATION_UPSTREAM_REVISION", "\"${forkVersion.getProperty("upstream.sha")}\"")
        val sourceRevision = providers.exec {
            commandLine("git", "describe", "--always", "--dirty", "--abbrev=40", "--exclude=*")
        }.standardOutput.asText.get().trim()
        buildConfigField("String", "AUTOMATION_SOURCE_REVISION", "\"$sourceRevision\"")
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("fgadebug.keystore")
            storePassword = "123abc"
            keyAlias = "fgadebug"
            keyPassword = "123abc"
        }
        create("release") {
            storeFile = file("fgautomata.keystore")
            storePassword = System.getenv("KEYSTORE_PASS")
            keyAlias = "fgautomata"
            keyPassword = System.getenv("KEYSTORE_PASS")
        }
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
            applicationIdSuffix = ".test"
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = true
            // TODO test app extensively before enabling
            // isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        create("ci") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")

            ndk {
                //noinspection ChromeOsAbiSupport
                abiFilters.add("armeabi-v7a")
                abiFilters.add("arm64-v8a")
            }
        }
    }
    lint {
        abortOnError = false
        disable += "MissingTranslation"
    }
    // run tests in CI builds instad of debug
    testBuildType = "ci"

    namespace = "io.github.fate_grand_automata"
}

kotlin {
    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        optIn.add("androidx.compose.animation.ExperimentalAnimationApi")
        optIn.add("androidx.compose.ui.ExperimentalComposeUiApi")
        optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
        optIn.add("androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi")
    }
}

dependencies {
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(project(":automation"))
    implementation(project(":libautomata"))
    implementation(project(":scripts"))
    implementation(project(":prefs"))


    implementation(libs.kotlin.stdlib)

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.constraintlayout)

    implementation(libs.opencv)
    implementation(libs.tesseract4android)

    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.viewmodel.savedstate)
    implementation(libs.lifecycle.viewmodel.compose)


    implementation(libs.kotlinx.serialization.json)

    implementation(libs.dagger.hilt.android)
    ksp(libs.dagger.hilt.compiler)

    implementation(libs.timber)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.size)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)



    implementation(libs.accompanist.permissions)

    implementation(libs.google.android.play.update.ktx)
    implementation(libs.coil)
    implementation(libs.coil.gif)

    implementation(libs.reorderable)

    testImplementation(platform(libs.junit.bom)) {
        because("kotlin-test comes with conflicting junit versions")
    }
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.willowtreeapps.assertk)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    val supportAssets = layout.projectDirectory.dir("src/main/assets/Support")

    /*
     * Assets are not on the unit test classpath, and relying on the working directory breaks
     * as soon as the test is run from the IDE instead of Gradle.
     */
    systemProperty("fga.supportAssets", supportAssets.asFile.path)

    /*
     * Triggers the task when the support assets change, so that the system property above is always
     * up to date.
     */
    inputs.dir(supportAssets).withPathSensitivity(PathSensitivity.RELATIVE)
}
