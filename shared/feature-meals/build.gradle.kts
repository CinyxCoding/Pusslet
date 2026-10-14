plugins {
    id("pusslet.kmp.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // BackHandler: the recipe editor asks "Släng ändringarna?" on Back. Same version as the rest of Compose Multiplatform.
            implementation(libs.compose.ui.backhandler)
        }
    }
}
