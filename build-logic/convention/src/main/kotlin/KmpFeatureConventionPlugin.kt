import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** A feature module: Compose UI + ViewModels on top of core, data and the design system. */
class KmpFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("pusslet.kmp.compose")

        val libs = libs
        extensions.configure<KotlinMultiplatformExtension> {
            sourceSets.getByName("commonMain").dependencies {
                implementation(project(":shared:core"))
                implementation(project(":shared:data"))
                implementation(project(":shared:ui"))
                implementation(libs.findLibrary("kotlinx-coroutines-core").get())
                implementation(libs.findLibrary("kotlinx-datetime").get())
                implementation(libs.findLibrary("kermit").get())
            }
            sourceSets.getByName("commonTest").dependencies {
                implementation(libs.findLibrary("turbine").get())
                implementation(project(":shared:testing"))
            }
        }
    }
}
