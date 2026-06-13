package com.stash.data.download.lossless.lucida

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class LucidaToken(
    val expiry: Long,
    val primary: String,
    val secondary: String? = null
)

@Serializable
data class LucidaAccount(
    val id: String,
    val type: String
)

@Serializable
data class LucidaUpload(
    val enabled: Boolean
)

@Serializable
data class LucidaDownloadRequest(
    val account: LucidaAccount,
    val compat: Boolean,
    val downscale: String,
    val handoff: Boolean,
    val metadata: Boolean,
    @SerialName("private") val isPrivate: Boolean,
    val token: LucidaToken,
    val upload: LucidaUpload,
    val url: String
)

@Serializable
data class LucidaDownloadResponse(
    val handoff: String? = null,
    val server: String? = null,
    val error: String? = null
)

@Serializable
data class LucidaStatusResponse(
    val status: String,
    val message: String? = null
)

@Serializable
data class LucidaInfo(
    val type: String,
    val url: String? = null
)

@Serializable
data class LucidaPageData(
    val token: String,
    @SerialName("tokenExpiry") val tokenExpiry: Long,
    val info: LucidaInfo? = null
)

@Singleton
class LucidaApiClient @Inject constructor(
    sharedClient: OkHttpClient,
) {
    internal var httpClient: OkHttpClient = sharedClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", USER_AGENT)
                .build()
            chain.proceed(request)
        }
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    suspend fun resolvePageData(trackUrl: String): LucidaPageData? = withContext(Dispatchers.IO) {
        val url = "https://lucida.to/".toHttpUrl().newBuilder()
            .addQueryParameter("url", trackUrl)
            .addQueryParameter("country", "auto")
            .build()

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val html = response.body?.string().orEmpty()
                val startMarker = ",{\"type\":\"data\",\"data\":"
                val endMarker = ",\"uses\":{\"url\":1}}];\n"
                val startIndex = html.indexOf(startMarker)
                if (startIndex == -1) return@use null
                val dataStart = startIndex + startMarker.length
                val endIndex = html.indexOf(endMarker, dataStart)
                if (endIndex == -1) return@use null
                val jsonStr = html.substring(dataStart, endIndex)
                json.decodeFromString<LucidaPageData>(jsonStr)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve Lucida page data for $trackUrl", e)
            null
        }
    }

    suspend fun requestDownload(
        trackUrl: String,
        token: String,
        tokenExpiry: Long,
    ): LucidaDownloadResponse? = withContext(Dispatchers.IO) {
        val payload = LucidaDownloadRequest(
            account = LucidaAccount("auto", "country"),
            compat = false,
            downscale = "original",
            handoff = true,
            metadata = true,
            isPrivate = true,
            token = LucidaToken(tokenExpiry, token),
            upload = LucidaUpload(false),
            url = trackUrl
        )
        val requestBody = json.encodeToString(LucidaDownloadRequest.serializer(), payload)
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://lucida.to/api/load?url=%2Fapi%2Ffetch%2Fstream%2Fv2")
            .post(requestBody)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string().orEmpty()
                json.decodeFromString<LucidaDownloadResponse>(body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request Lucida download for $trackUrl", e)
            null
        }
    }

    suspend fun checkStatus(server: String, handoff: String): LucidaStatusResponse? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://$server.lucida.to/api/fetch/request/$handoff")
            .get()
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string().orEmpty()
                json.decodeFromString<LucidaStatusResponse>(body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check status for $handoff on server $server", e)
            null
        }
    }

    companion object {
        private const val TAG = "LucidaApiClient"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
