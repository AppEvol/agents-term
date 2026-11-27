import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.secrets)
    alias(libs.plugins.google.services)
}

val ciVersionCode = providers.gradleProperty("appVersionCode").orNull?.toIntOrNull()
    ?: providers.environmentVariable("APP_VERSION_CODE").orNull?.toIntOrNull()
    ?: 1
val ciVersionName = providers.gradleProperty("appVersionName").orNull?.takeIf { it.isNotBlank() }
    ?: providers.environmentVariable("APP_VERSION_NAME").orNull?.takeIf { it.isNotBlank() }
    ?: "0.3"
val daemonReleaseBaseUrl = providers.gradleProperty("daemonReleaseBaseUrl").orNull?.takeIf { it.isNotBlank() }
    ?: providers.environmentVariable("AGENTSTERM_DAEMON_RELEASE_BASE_URL").orNull?.takeIf { it.isNotBlank() }
    ?: "https://github.com/AppEvol/agents-term/releases/latest/download"

// "record" rewrites the golden images of the screenshot tests; anything else verifies them.
val goldensMode = providers.gradleProperty("goldens").orNull

// Robolectric needs Java 21 to run its Android SDK 36 sandbox, so unit tests always run on a
// Java 21 toolchain, whichever JDK runs Gradle. Gradle uses an installed JDK 21 if it finds one
// and otherwise downloads one (foojay resolver in settings.gradle.kts).
val unitTestJavaVersion = 21
val unitTestJavaLauncher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(unitTestJavaVersion)
}

// If this checkout is a linked git worktree, resolve the main checkout directory from `.git`.
fun resolveMainWorktreeDir(projectRoot: File): File? {
    val dotGit = projectRoot.resolve(".git")
    if (!dotGit.isFile) return null
    val gitDirPath = dotGit.readLines()
        .firstOrNull { it.startsWith("gitdir:") }
        ?.removePrefix("gitdir:")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    val gitDir = projectRoot.resolve(gitDirPath).normalize()
    val commonDirFile = gitDir.resolve("commondir")
    val commonGitDir = if (commonDirFile.isFile) {
        val commonDirPath = commonDirFile.readText().trim()
        if (commonDirPath.isEmpty()) return null
        gitDir.resolve(commonDirPath).normalize()
    } else {
        gitDir
    }
    return commonGitDir.parentFile?.takeIf { it.isDirectory }
}

// Prefer `$rootDir/debug.keystore` (CI decodes DEBUG_KEYSTORE_BASE64 here), then the main
// worktree's `debug.keystore` for linked git worktrees; if neither exists, fall back to AGP's
// built-in `debug` signing config (`~/.android/debug.keystore`, auto-created if missing).
val resolvedDebugKeystore = listOfNotNull(
    rootProject.file("debug.keystore"),
    resolveMainWorktreeDir(rootDir)?.resolve("debug.keystore"),
).firstOrNull { it.isFile }

android {
    namespace = "com.example"
    compileSdk { version = release(36) { minorApiLevel = 1 } }

    defaultConfig {
        applicationId = "com.aistudio.herdrmobile.k7x2qp"
        minSdk = 24
        targetSdk = 36
        versionCode = ciVersionCode
        versionName = ciVersionName
        buildConfigField("String", "DAEMON_RELEASE_BASE_URL", "\"$daemonReleaseBaseUrl\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Do not remove or modify these signingConfigs. They are necessary for building, installing,
    // and updating Android apps in AI Studio.
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH") ?: "$rootDir/my-upload-key.jks"
            storeFile = file(keystorePath)
            storePassword = System.getenv("STORE_PASSWORD")
            keyAlias = "upload"
            keyPassword = System.getenv("KEY_PASSWORD")
        }
        create("debugConfig") {
            storeFile = resolvedDebugKeystore ?: file("$rootDir/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isCrunchPngs = false
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
            // AI Studio.
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Use debugConfig when a repo/main-worktree debug.keystore is available; otherwise
            // fall back to AGP's default debug signing config so fresh clones/worktrees build
            // without manual keystore setup.
            signingConfig = if (resolvedDebugKeystore != null) {
                signingConfigs.getByName("debugConfig")
            } else {
                signingConfigs.getByName("debug")
            }
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
    sourceSets {
        getByName("main") {
            jniLibs.directories.add(layout.buildDirectory.dir("ghostty-vt/jniLibs").get().asFile.path)
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                test.javaLauncher = unitTestJavaLauncher
                val ghosttyHostLibDir = layout.buildDirectory.dir("ghostty-vt/host").get().asFile.absolutePath
                val existingLibPath = System.getProperty("java.library.path").orEmpty()
                test.systemProperty(
                    "java.library.path",
                    if (existingLibPath.isNotEmpty()) {
                        "$ghosttyHostLibDir${File.pathSeparator}$existingLibPath"
                    } else {
                        ghosttyHostLibDir
                    },
                )
                // Golden image tests (MarkdownGoldenTest) compare against src/test/snapshots.
                // `-Pgoldens=record` writes new golden images instead of comparing.
                val record = goldensMode == "record"
                test.systemProperty("roborazzi.test.record", record)
                test.systemProperty("roborazzi.test.verify", !record)
                test.inputs.dir("src/test/snapshots").withPathSensitivity(PathSensitivity.RELATIVE)
                if (record) test.outputs.upToDateWhen { false }
            }
        }
    }
    lint {
        // Android Lint is part of CI (`gradle :app:lintDebug`): any warning fails the build.
        warningsAsErrors = true
        abortOnError = true
        // Findings that predate the check. Fix them rather than adding to this file.
        baseline = file("lint-baseline.xml")
        // "A newer version exists" nags would break CI whenever a dependency or SDK ships a release.
        disable += listOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
    packaging {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/INDEX.LIST",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
    propertiesFileName = ".env"
    defaultPropertiesFileName = ".env.example"
    ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
    implementation(platform(libs.androidx.compose.bom))
    // implementation(platform(libs.firebase.bom))
    // implementation(libs.accompanist.permissions)
    implementation(libs.androidx.activity.compose)
    // implementation(libs.androidx.camera.camera2)
    // implementation(libs.androidx.camera.core)
    // implementation(libs.androidx.camera.lifecycle)
    // implementation(libs.androidx.camera.view)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    // implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.autolink)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.task.list.items)
    // implementation(libs.androidx.navigation.compose)
    // implementation(libs.androidx.room.ktx)
    // implementation(libs.androidx.room.runtime)
    // implementation(libs.coil.compose)
    // implementation(libs.converter.moshi)
    // implementation(libs.firebase.ai)
    // Uncomment to use Firestore:
    // implementation(libs.firebase.firestore)

    // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
    // Sign-In via Credential Manager:
    // implementation(libs.firebase.auth)
    // implementation(libs.androidx.credentials)
    // implementation(libs.androidx.credentials.play.services)
    // implementation(libs.googleid)
    // implementation(libs.firebase.appcheck.recaptcha)
    // implementation(libs.firebase.appcheck.debug)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    // implementation(libs.logging.interceptor)
    implementation(libs.moshi.kotlin)
    implementation(libs.mwiede.jsch)
    implementation(libs.okhttp)
    // implementation(libs.play.services.location)
    // implementation(libs.retrofit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.core)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.sshd.core)
    androidTestImplementation(libs.sshd.common)
    androidTestImplementation(libs.slf4j.simple)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // "ksp"(libs.androidx.room.compiler)
    "ksp"(libs.moshi.kotlin.codegen)
}

val buildGhosttyVtHostNative = tasks.register<Exec>("buildGhosttyVtHostNative") {
    description = "Builds libghostty-vt 1.0.2 and the host Linux JNI library for JVM unit tests."
    workingDir = rootProject.projectDir
    commandLine("bash", rootProject.file("scripts/build-libghostty-vt.sh").absolutePath, "--host-only")
    inputs.file(file("src/main/jni/ghostty_vt_jni.c"))
    inputs.file(rootProject.file("scripts/build-libghostty-vt.sh"))
    outputs.file(layout.buildDirectory.file("ghostty-vt/host/libghostty_vt_jni.so"))
}

val buildGhosttyVtAndroidNative = tasks.register<Exec>("buildGhosttyVtAndroidNative") {
    description = "Cross-builds libghostty-vt 1.0.2 and the Android JNI libraries."
    workingDir = rootProject.projectDir
    commandLine("bash", rootProject.file("scripts/build-libghostty-vt.sh").absolutePath, "--android-only")
    inputs.file(file("src/main/jni/ghostty_vt_jni.c"))
    inputs.file(rootProject.file("scripts/build-libghostty-vt.sh"))
    outputs.dir(layout.buildDirectory.dir("ghostty-vt/jniLibs"))
}

tasks.withType<Test>().configureEach {
    dependsOn(buildGhosttyVtHostNative)
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(buildGhosttyVtAndroidNative)
}
