package dev.astra.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One PC or phone as the registry reports it (GET /api/devices).
 * A phone that shares its screen is reached through the Pi: its hostname/port are the Pi's
 * and `path` is "/relay/d/<id>" (see registry/relay.py). For a PC, path is "".
 */
data class RegistryDevice(
    val id: Int,
    val name: String,
    val hostname: String,
    val status: String,
    val lastSeenAt: String?,
    val port: Int,
    val scheme: String,
    val paired: Boolean?,
    val os: String? = null,
    val path: String = "",
    val relay: Boolean = false,
) {
    val online get() = status == "online"

    companion object {
        fun fromJson(o: JSONObject) = RegistryDevice(
            id = o.getInt("id"),
            name = o.getString("name"),
            hostname = o.getString("hostname"),
            status = o.optString("status", "offline"),
            lastSeenAt = o.optString("last_seen_at").takeIf { it.isNotEmpty() && it != "null" },
            port = o.optInt("port", 8765),
            scheme = o.optString("scheme", "http"),
            paired = if (o.has("paired")) o.getBoolean("paired") else null,
            os = o.optString("os").takeIf { it.isNotEmpty() && it != "null" },
            path = o.optString("path", "").takeIf { it.startsWith("/relay/") } ?: "",
            relay = o.optBoolean("relay", false),
        )
    }
}

/** A PC or phone this phone has paired with, saved on the phone (Kanban T-30). */
data class PairedDevice(
    val id: Int,
    val name: String,
    val host: String,
    val port: Int,
    val scheme: String,
    val path: String = "",
) {
    val baseUrl get() = "$scheme://$host:$port$path"
    val isPhone get() = path.isNotEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("host", host).put("port", port).put("scheme", scheme).put("path", path)

    companion object {
        fun fromJson(o: JSONObject) = PairedDevice(
            o.getInt("id"), o.getString("name"), o.getString("host"),
            o.optInt("port", 8765), o.optString("scheme", "http"), o.optString("path", ""),
        )

        fun listFromJson(text: String?): List<PairedDevice> {
            if (text.isNullOrBlank()) return emptyList()
            val arr = JSONArray(text)
            return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }

        fun listToJson(list: List<PairedDevice>): String =
            JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
    }
}

/**
 * What the PC's QR code carries (P1 -> B2): name and Tailscale address, never the PIN.
 * Example: {"astra":1,"device_id":3,"name":"DESKTOP-PC","host":"desktop-pc.tail1234.ts.net",
 *           "ip":"100.64.0.5","port":8765,"scheme":"http"}
 */
data class PairTarget(
    val deviceId: Int,
    val name: String,
    val host: String,
    val port: Int,
    val scheme: String,
    val path: String = "",
) {
    val baseUrl get() = "$scheme://$host:$port$path"

    fun toPaired() = PairedDevice(deviceId, name, host, port, scheme, path)

    companion object {
        /** Returns null for any QR that is not from this app. */
        fun fromQr(text: String): PairTarget? = try {
            val o = JSONObject(text)
            if (o.optInt("astra", 0) != 1) null
            else {
                val host = o.optString("host").takeIf { it.isNotBlank() && it != "null" }
                    ?: o.optString("ip").takeIf { it.isNotBlank() && it != "null" }
                if (host == null) null else PairTarget(
                    deviceId = o.getInt("device_id"),
                    name = o.getString("name"),
                    host = host,
                    port = o.optInt("port", 8765),
                    scheme = if (o.optString("scheme") == "https") "https" else "http",
                    path = o.optString("path").takeIf { it.startsWith("/relay/") } ?: "",
                )
            }
        } catch (e: Exception) {
            null
        }

        fun fromRegistry(d: RegistryDevice) = PairTarget(d.id, d.name, d.hostname, d.port, d.scheme, d.path)
    }
}

enum class MouseMode(val param: String, val label: String) { DIRECT("direct", "Direct touch"), TRACKPAD("trackpad", "Trackpad") }
enum class Quality(val param: String, val label: String) { AUTO("auto", "Auto"), HIGH("high", "High"), LOW("low", "Low (data saver)") }

data class Settings(
    val registryUrl: String,
    val phoneToken: String?,
    val phoneName: String,
    val mouseMode: MouseMode,
    val quality: Quality,
    val paired: List<PairedDevice>,
    /** "Share this phone": the Pi's HOST_API_KEY, so this phone can act as a host. */
    val hostKey: String?,
    /** Random id that keeps this phone the same device on the Pi across restarts. */
    val installId: String?,
    /** Let phones paired with this one in without the Allow prompt (default off). */
    val autoAllowPaired: Boolean,
)

/**
 * What the Pi's `show-phone-key.sh` QR code carries, to set up "Share this phone":
 * {"astra_host":1,"registry":"http://100.101.102.103:8000","key":"<HOST_API_KEY>"}
 */
data class HostSetup(val registryUrl: String?, val key: String) {
    companion object {
        fun fromQr(text: String): HostSetup? = try {
            val o = JSONObject(text)
            val key = o.optString("key")
            if (o.optInt("astra_host", 0) != 1 || key.length < 16) null
            else HostSetup(o.optString("registry").takeIf { it.startsWith("http") }?.trimEnd('/'), key)
        } catch (e: Exception) {
            null
        }
    }
}
