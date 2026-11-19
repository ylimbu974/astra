package dev.astra.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Every server error has the shape {"error": code, "detail": text} (Sketch, Section 4). */
class ApiException(val status: Int, val code: String, val detail: String, val body: JSONObject? = null) :
    Exception("$status $code: $detail")

private val JSON = "application/json".toMediaType()

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Pairing waits while the person at the PC decides (up to 60 s on P2). */
    val slow: OkHttpClient = client.newBuilder().readTimeout(80, TimeUnit.SECONDS).build()

    suspend fun call(
        method: String, url: String, body: JSONObject? = null, token: String? = null, slowCall: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).apply {
            if (token != null) header("Authorization", "Bearer $token")
            method(method, body?.toString()?.toRequestBody(JSON))
        }.build()
        try {
            (if (slowCall) slow else client).newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val o = runCatching { JSONObject(text) }.getOrNull()
                    throw ApiException(resp.code, o?.optString("error") ?: "http_${resp.code}",
                        o?.optString("detail") ?: text.take(200), o)
                }
                text
            }
        } catch (e: IOException) {
            throw ApiException(0, "unreachable", e.message ?: "Network error")
        }
    }
}

/** The Pi registry (REST over the tailnet). */
class RegistryApi(private val baseUrl: String, private val token: String?) {
    suspend fun health(): Boolean = runCatching {
        JSONObject(Http.call("GET", "$baseUrl/health")).optBoolean("ok")
    }.getOrDefault(false)

    suspend fun devices(): List<RegistryDevice> {
        val arr = JSONArray(Http.call("GET", "$baseUrl/api/devices", token = token))
        return (0 until arr.length()).map { RegistryDevice.fromJson(arr.getJSONObject(it)) }
    }

    /** B3 step 1: returns (pairingId, newTokenOrNull). */
    suspend fun startPairing(deviceId: Int, phoneName: String, pin: String): Pair<Int, String?> {
        val o = JSONObject(Http.call("POST", "$baseUrl/api/pairings", token = token, body = JSONObject()
            .put("device_id", deviceId).put("phone_name", phoneName).put("pin", pin)))
        return o.getInt("pairing_id") to o.optString("phone_token").takeIf { it.isNotEmpty() }
    }

    suspend fun removePairing(deviceId: Int) {
        Http.call("DELETE", "$baseUrl/api/pairings/device/$deviceId", token = token)
    }

    suspend fun renamePhone(name: String) {
        Http.call("PATCH", "$baseUrl/api/phones/me", token = token, body = JSONObject().put("name", name))
    }
}

/** The Host Agent on a PC. */
object HostApi {
    /** B3 step 2: PIN check on the PC, then the PC owner taps Allow / Deny on P2. */
    suspend fun pair(target: PairTarget, pairingId: Int, pin: String, phoneName: String) {
        Http.call("POST", "${target.baseUrl}/api/pair", slowCall = true, body = JSONObject()
            .put("pairing_id", pairingId).put("pin", pin).put("phone_name", phoneName))
    }
}
