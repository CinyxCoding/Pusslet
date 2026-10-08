plugins {
    id("pusslet.kmp.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.core)
            api(libs.compose.components.resources)
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "se.pusslet.ui.resources"
    generateResClass = always
}
