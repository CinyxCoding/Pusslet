import dev.detekt.gradle.Detekt
import dev.detekt.gradle.DetektCreateBaselineTask
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jlleitschuh.gradle.ktlint.KtlintExtension

/** ktlint (formatting) + detekt (static analysis) with shared configuration in /config. */
class QualityConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jlleitschuh.gradle.ktlint")
        pluginManager.apply("dev.detekt")

        extensions.configure<KtlintExtension> {
            android.set(true)
            ignoreFailures.set(false)
            filter {
                exclude { it.file.path.contains("${java.io.File.separator}build${java.io.File.separator}") }
            }
        }

        extensions.configure<DetektExtension> {
            buildUponDefaultConfig.set(true)
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
            parallel.set(true)
            source.setFrom(fileTree("src") { include("**/*.kt") })
            val baselineFile = rootProject.file("config/detekt/baseline-${project.name}.xml")
            if (baselineFile.exists()) baseline.set(baselineFile)
        }

        tasks.withType(Detekt::class.java).configureEach { jvmTarget.set("17") }
        tasks.withType(DetektCreateBaselineTask::class.java).configureEach { jvmTarget.set("17") }
    }
}
