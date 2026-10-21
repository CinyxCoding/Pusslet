plugins {
    id("pusslet.kmp.feature")
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            // Recipe import is tested against pages served by a mock engine.
            implementation(libs.ktor.client.mock)
        }
    }
}
