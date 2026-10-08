plugins {
    `kotlin-dsl`
}

group = "se.pusslet.buildlogic"

dependencies {
    compileOnly(libs.gradle.plugin.android)
    compileOnly(libs.gradle.plugin.kotlin)
    compileOnly(libs.gradle.plugin.compose)
    compileOnly(libs.gradle.plugin.compose.compiler)
    compileOnly(libs.gradle.plugin.ktlint)
    compileOnly(libs.gradle.plugin.detekt)
}

gradlePlugin {
    plugins {
        register("kmpLibrary") {
            id = "pusslet.kmp.library"
            implementationClass = "KmpLibraryConventionPlugin"
        }
        register("kmpCompose") {
            id = "pusslet.kmp.compose"
            implementationClass = "KmpComposeConventionPlugin"
        }
        register("kmpFeature") {
            id = "pusslet.kmp.feature"
            implementationClass = "KmpFeatureConventionPlugin"
        }
        register("quality") {
            id = "pusslet.quality"
            implementationClass = "QualityConventionPlugin"
        }
    }
}
