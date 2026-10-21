import com.android.build.api.dsl.ApplicationProductFlavor
import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.Duration
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
    id("pusslet.quality")
}

val appName = providers.gradleProperty("pusslet.appName").getOrElse("Pusslet")
val baseApplicationId = "se.pusslet.app"

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/** The Android emulator reaches the host as 10.0.2.2; set pusslet.emulatorHost in local.properties for a physical device. */
val emulatorHost: String = localProps.getProperty("pusslet.emulatorHost", "10.0.2.2")

/** Host used for invite links (Android App Links), e.g. your-project.web.app. */
val linkHost: String = localProps.getProperty("pusslet.linkHost", "pusslet.web.app")

/**
 * Reads Firebase options from src/<flavor>/google-services.json when present (gitignored, only the owner has it).
 * Missing file = empty values = the app runs local-only. No Google Services Gradle plugin needed (ADR-013).
 */
@Suppress("UNCHECKED_CAST")
fun firebaseOptions(flavor: String, packageName: String): Map<String, String> {
    val file = file("src/$flavor/google-services.json")
    if (!file.exists()) return emptyMap()
    val json = JsonSlurper().parse(file) as Map<String, Any?>
    val project = json["project_info"] as Map<String, Any?>
    val clients = json["client"] as List<Map<String, Any?>>

    fun pkg(c: Map<String, Any?>) = ((c["client_info"] as Map<String, Any?>)["android_client_info"] as Map<String, Any?>)["package_name"]
    val client = clients.firstOrNull { pkg(it) == packageName } ?: clients.first()
    val apiKey = ((client["api_key"] as List<Map<String, Any?>>).first())["current_key"].toString()
    return mapOf(
        "PROJECT_ID" to project["project_id"].toString(),
        "APP_ID" to (client["client_info"] as Map<String, Any?>)["mobilesdk_app_id"].toString(),
        "API_KEY" to apiKey,
        "SENDER_ID" to project["project_number"].toString(),
        "STORAGE_BUCKET" to (project["storage_bucket"] ?: "").toString(),
        // OAuth web client (type 3) is what Google sign-in via Credential Manager needs.
        "WEB_CLIENT_ID" to
            (
                (client["oauth_client"] as? List<Map<String, Any?>>).orEmpty().firstOrNull {
                    it["client_type"].toString() == "3"
                }?.get("client_id")
                    ?: ""
            ).toString(),
    )
}

fun ApplicationProductFlavor.firebase(options: Map<String, String>) {
    listOf("PROJECT_ID", "APP_ID", "API_KEY", "SENDER_ID", "STORAGE_BUCKET", "WEB_CLIENT_ID").forEach { key ->
        val value = options[key].orEmpty()
        buildConfigField("String", "FIREBASE_$key", "\"$value\"")
    }
}

android {
    namespace = "se.pusslet.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = baseApplicationId
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = libs.versions.app.versionCode.get().toInt()
        versionName = libs.versions.app.versionName.get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue("string", "app_name", appName)
        buildConfigField("String", "EMULATOR_HOST", "\"$emulatorHost\"")
        buildConfigField("String", "LINK_HOST", "\"$linkHost\"")
        manifestPlaceholders["linkHost"] = linkHost
    }

    flavorDimensions += "env"
    productFlavors {
        // Firebase Local Emulator Suite + seed data ("Familjen Lind"). Needs no keys at all.
        create("demo") {
            dimension = "env"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            resValue("string", "app_name", "$appName Demo")
            buildConfigField("String", "BACKEND", "\"emulator\"")
            buildConfigField("boolean", "SEED_DEMO_DATA", "true")
            buildConfigField("boolean", "DEVELOPER_TOOLS", "true")
            // "demo-" project ids make the emulators run without any real Firebase project.
            firebase(
                mapOf(
                    "PROJECT_ID" to "demo-pusslet",
                    "APP_ID" to "1:000000000000:android:0000000000000000",
                    "API_KEY" to "demo-not-a-secret",
                    "SENDER_ID" to "000000000000",
                ),
            )
        }
        // Real Firebase project for development (google-services.json in src/dev/).
        create("dev") {
            dimension = "env"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            resValue("string", "app_name", "$appName Dev")
            buildConfigField("String", "BACKEND", "\"firebase\"")
            buildConfigField("boolean", "SEED_DEMO_DATA", "false")
            buildConfigField("boolean", "DEVELOPER_TOOLS", "true")
            firebase(firebaseOptions("dev", "$baseApplicationId.dev"))
        }
        // Production (google-services.json in src/prod/).
        create("prod") {
            dimension = "env"
            buildConfigField("String", "BACKEND", "\"firebase\"")
            buildConfigField("boolean", "SEED_DEMO_DATA", "false")
            buildConfigField("boolean", "DEVELOPER_TOOLS", "false")
            firebase(firebaseOptions("prod", baseApplicationId))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                // Robolectric pokes at JDK internals; needed on JDK 21+.
                it.jvmArgs(
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                )
                it.maxHeapSize = "2g"
                // Name each test in the log and fail instead of hanging the CI job when a screen never settles.
                it.testLogging { events("started", "passed", "skipped", "failed") }
                it.timeout.set(Duration.ofMinutes(15))
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.time.ExperimentalTime",
            "-opt-in=kotlin.uuid.ExperimentalUuidApi",
        )
    }
}

dependencies {
    implementation(projects.shared.app)
    implementation(projects.shared.core)
    implementation(projects.shared.data)
    implementation(projects.shared.ui)

    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.components.resources)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.kermit)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.work.runtime)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.sqlite.framework)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.serialization.kotlinx.json)
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

roborazzi {
    outputDir.set(layout.projectDirectory.dir("src/test/snapshots"))
}
