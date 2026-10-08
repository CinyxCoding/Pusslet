plugins {
    id("pusslet.kmp.compose")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "PussletShared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.shared.core)
            api(projects.shared.data)
            api(projects.shared.ui)
            implementation(projects.shared.featureToday)
            implementation(projects.shared.featureCalendar)
            implementation(projects.shared.featureMeals)
            implementation(projects.shared.featureShopping)
            implementation(projects.shared.featureTasks)
            implementation(projects.shared.featureFamily)
            implementation(projects.shared.featureSettings)
            implementation(libs.androidx.navigation3.ui)
            implementation(libs.androidx.lifecycle.viewmodel.navigation3)
            implementation(libs.kermit)
        }
    }
}
