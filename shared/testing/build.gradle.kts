plugins {
    id("pusslet.kmp.library")
}

// In-memory fakes and a ready-made repository graph for ViewModel tests in the feature modules.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.core)
            api(projects.shared.data)
            api(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(project.dependencies.platform(libs.koin.bom))
        }
    }
}
