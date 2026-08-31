import java.util.Properties

plugins {
    id("stash.android.library")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

// ── ARCOD private stream endpoint ────────────────────────────────────────────
// The arcod operator shared a private streaming endpoint on the condition it not
// be exposed in the public repo. Its base URL (host + path) is therefore injected
// at build time from `local.properties` (gitignored) or an env var (CI/release),
// never committed to source. Set it in local.properties as:
//   arcod.streamBase=<base url including path>
// Empty is valid — an unconfigured build simply skips ARCOD streaming and fails
// over to the next source, exactly like a missing Last.fm key no-ops scrobbling.
val arcodLocalProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val arcodStreamBase: String =
    arcodLocalProperties.getProperty("arcod.streamBase") ?: System.getenv("ARCOD_STREAM_BASE").orEmpty()
// Private integration key for ARCOD's /v2/stash routes (per build, sent as the
// X-Stash-Key header). Rotated by the operator (Fufu) — keep it out of source,
// inject from local.properties / the ARCOD_STASH_KEY CI secret. Empty = ARCOD
// /v2/stash calls 403 and the source fails over.
val arcodStashKey: String =
    arcodLocalProperties.getProperty("arcod.stashKey") ?: System.getenv("ARCOD_STASH_KEY").orEmpty()

// ── Build-time config reader ───────────────────────────────────────────────
// Reads a value from local.properties (gitignored, local dev) falling back to an
// env var (CI/release). Empty is always valid — every field below no-ops when
// unset rather than failing the build.
val qbdlxProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun qbdlxProp(key: String, env: String) =
    qbdlxProps.getProperty(key) ?: System.getenv(env).orEmpty()

// ── Stash Lossless Relay runtime config ────────────────────────────────────
// URL of the ECDSA-signed lossless.json (the app fetches `<url>` and `<url>.sig`)
// and the base64 X.509 SPKI public key that verifies it. Both empty → the
// fetcher is disabled and the app has no relay (BYO / custom endpoint only).
// The APK never contains a relay hostname; the list lives behind this URL.
val losslessConfigUrl = qbdlxProp("lossless.configUrl", "LOSSLESS_CONFIG_URL")
val losslessConfigPubKey = qbdlxProp("lossless.configPubKey", "LOSSLESS_CONFIG_PUBKEY")

// What makes an ARCOD build usable is the /v2/stash integration key — the old
// private stream base is no longer the gate (those routes were retired when the
// operator moved Stash to /v2/stash). Keyless build → arcod can only 403, so the
// registries skip it entirely.
val arcodConfigured = arcodStashKey.isNotBlank()

android {
    namespace = "com.stash.data.download"

    defaultConfig {
        // Private ARCOD stream base (host+path), injected from local.properties /
        // env at build time so it never lives in the public repo. Empty when
        // unconfigured — ARCOD streaming then no-ops and the registry fails over.
        buildConfigField("String", "ARCOD_STREAM_BASE", "\"$arcodStreamBase\"")
        buildConfigField("String", "ARCOD_STASH_KEY", "\"$arcodStashKey\"")
        // Public host root for ARCOD's /v2/stash routes (Fufu published it openly;
        // only X-Stash-Key is private). Hardcoded, not injected.
        buildConfigField("String", "ARCOD_API_BASE", "\"https://api.arcod.xyz\"")
        buildConfigField("String", "LOSSLESS_CONFIG_URL", "\"$losslessConfigUrl\"")
        buildConfigField("String", "LOSSLESS_CONFIG_PUBKEY", "\"$losslessConfigPubKey\"")
        buildConfigField("Boolean", "ARCOD_CONFIGURED", "$arcodConfigured")
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Return Kotlin defaults (Unit) from stubbed Android SDK methods —
            // needed so android.util.Log calls inside production code don't
            // throw "not mocked" during JVM unit tests.
            isReturnDefaultValues = true
            // Required for Robolectric-backed DataStore tests
            // (LosslessSourcePreferencesYoutubeFallbackTest) to resolve
            // ApplicationProvider/preferencesDataStore against android resources.
            isIncludeAndroidResources = true
        }
    }

    packaging {
        jniLibs {
            // Required by the instrumented MetadataEmbeddingIntegrationTest:
            // FFmpeg.init unpacks libffmpeg.zip.so from nativeLibraryDir, which
            // only exists on-disk when extractNativeLibs="true". Mirrors
            // app/build.gradle.kts so the test APK behaves like the real app.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:auth"))
    implementation(project(":core:network"))
    implementation(project(":data:ytmusic"))
    implementation(project(":data:spotify"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.youtubedl.android)
    implementation(libs.youtubedl.ffmpeg)
    implementation(libs.youtubedl.aria2c)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // OkHttp for the lossless-source HTTP clients (Qobuz API, future
    // Bandcamp / Internet Archive). The yt-dlp-bound paths use the
    // youtubedl-android wrapper instead.
    implementation(libs.okhttp)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    // SAF support for writing downloads to a user-chosen external-storage tree
    // (SD card / USB-OTG). DocumentFile wraps the raw content-tree Uri.
    implementation("androidx.documentfile:documentfile:1.0.1")
    // media3-datasource provides DataSpec, CacheDataSource, SimpleCache,
    // HttpDataSource.Factory, and CacheKeyFactory for SearchDownloadCoordinator.
    // media3-database provides DatabaseProvider (transitive dep of SimpleCache).
    // Not declared in :core:media because that module already pulls them
    // transitively, but :data:download is a leaf that doesn't depend on
    // :core:media (circular — core:media depends on data:download).
    implementation(libs.media3.datasource)
    implementation(libs.media3.database)

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.truth)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    // MockK for QobuzSource tests — suspend-function mocking is cleaner
    // than Mockito's, and matches the pattern used in :core:media tests.
    testImplementation(libs.mockk)
    // MockWebServer for QobuzApiClient tests — fake server, real OkHttp client.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Robolectric — Android environment for DataStore-backed pref tests
    // (LosslessSourcePreferencesYoutubeFallbackTest), mirroring the
    // EqStoreTest setup in :core:media.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    // Instrumented tests — MetadataEmbeddingIntegrationTest runs against the
    // ffmpeg .so bundled by youtubedl-android on a real device, since that
    // shell-out is the only place where Opus attached_pic + Vorbis-comment
    // casing claims can be verified end-to-end.
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
