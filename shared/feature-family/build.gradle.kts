plugins {
    id("pusslet.kmp.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.qrose)
        }
    }
}
