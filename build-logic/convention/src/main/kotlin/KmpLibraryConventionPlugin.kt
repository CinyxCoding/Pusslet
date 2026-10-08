import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Base setup for every shared module: Android (KMP library plugin) + iOS targets,
 * host tests enabled, common test dependencies and quality tooling.
 *
 * The Android namespace is derived from the module path: `:shared:feature-today` -> `se.pusslet.feature.today`.
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("com.android.kotlin.multiplatform.library")
        pluginManager.apply("pusslet.quality")

        val libs = libs
        extensions.configure<KotlinMultiplatformExtension> {
            (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
                namespace = pussletNamespace()
                compileSdk = libs.version("android-compileSdk").toInt()
                minSdk = libs.version("android-minSdk").toInt()
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
                withHostTest {
                    isReturnDefaultValues = true
                }
            }

            iosArm64()
            iosSimulatorArm64()

            applyDefaultHierarchyTemplate()

            compilerOptions {
                freeCompilerArgs.addAll(
                    "-Xexpect-actual-classes",
                    "-opt-in=kotlin.time.ExperimentalTime",
                    "-opt-in=kotlin.uuid.ExperimentalUuidApi",
                )
            }

            sourceSets.getByName("commonTest").dependencies {
                implementation(libs.findLibrary("kotlin-test").get())
                implementation(libs.findLibrary("kotlinx-coroutines-test").get())
            }
        }
    }
}

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String = findVersion(alias).get().requiredVersion

internal fun Project.pussletNamespace(): String {
    val segments = path.removePrefix(":").split(":").filter { it != "shared" }
    val name = segments.joinToString(".") { it.replace("-", ".") }
    return "se.pusslet.$name"
}
