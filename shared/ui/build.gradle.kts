plugins {
    id("pusslet.kmp.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.core)
            api(libs.compose.components.resources)
            // BackHandler for UnsavedChangesBackHandler. Same version as the rest of Compose Multiplatform.
            implementation(libs.compose.ui.backhandler)
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "se.pusslet.ui.resources"
    generateResClass = always
}
