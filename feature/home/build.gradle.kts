plugins {
    id("stash.android.feature")
}
android {
    namespace = "com.stash.feature.home"
}
dependencies {
    implementation(project(":core:auth"))
    implementation(project(":core:data"))
    implementation(project(":core:media"))
    // For FileOrganizer.getTotalStorageBytes / getLosslessStorageBytes —
    // disk-truth Storage stats on the Home sync card (bypassing the
    // unreliable DB `file_size_bytes` SUM for legacy libraries).
    implementation(project(":data:download"))
    implementation(libs.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}
