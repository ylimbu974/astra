package dev.astra.app.data

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.astra.app.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "astra")

/** Everything the app remembers, in one small DataStore file (Kanban T-30). */
class Store(private val context: Context) {
    private object K {
        val registry = stringPreferencesKey("registry_url")
        val token = stringPreferencesKey("phone_token")
        val phoneName = stringPreferencesKey("phone_name")
        val mouse = stringPreferencesKey("mouse_mode")
        val quality = stringPreferencesKey("quality")
        val paired = stringPreferencesKey("paired_devices")
        val hostKey = stringPreferencesKey("host_key")
        val installId = stringPreferencesKey("install_id")
        val autoAllow = booleanPreferencesKey("auto_allow_paired")
    }

    private fun Preferences.toSettings() = Settings(
        registryUrl = this[K.registry] ?: BuildConfig.DEFAULT_REGISTRY_URL,
        phoneToken = this[K.token],
        phoneName = this[K.phoneName] ?: defaultPhoneName(),
        mouseMode = MouseMode.entries.firstOrNull { it.param == this[K.mouse] } ?: MouseMode.DIRECT,
        quality = Quality.entries.firstOrNull { it.param == this[K.quality] } ?: Quality.AUTO,
        paired = PairedDevice.listFromJson(this[K.paired]),
        hostKey = this[K.hostKey],
        installId = this[K.installId],
        autoAllowPaired = this[K.autoAllow] ?: false,
    )

    val settings: Flow<Settings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): Settings = settings.first()

    suspend fun setRegistryUrl(url: String) = context.dataStore.edit { it[K.registry] = url.trim().trimEnd('/') }
    suspend fun setToken(token: String) = context.dataStore.edit { it[K.token] = token }
    suspend fun setPhoneName(name: String) = context.dataStore.edit { it[K.phoneName] = name.trim() }
    suspend fun setMouseMode(m: MouseMode) = context.dataStore.edit { it[K.mouse] = m.param }
    suspend fun setQuality(q: Quality) = context.dataStore.edit { it[K.quality] = q.param }

    suspend fun setHostKey(key: String?) = context.dataStore.edit {
        if (key.isNullOrBlank()) it.remove(K.hostKey) else it[K.hostKey] = key.trim()
    }
    suspend fun setAutoAllow(on: Boolean) = context.dataStore.edit { it[K.autoAllow] = on }

    /** Created once and kept: the Pi knows this phone by it. */
    suspend fun installId(): String {
        current().installId?.let { return it }
        val id = java.util.UUID.randomUUID().toString().replace("-", "").take(24)
        context.dataStore.edit { if (it[K.installId] == null) it[K.installId] = id }
        return current().installId ?: id
    }

    suspend fun savePaired(device: PairedDevice) = context.dataStore.edit { prefs ->
        val list = PairedDevice.listFromJson(prefs[K.paired]).filterNot { it.id == device.id } + device
        prefs[K.paired] = PairedDevice.listToJson(list.sortedBy { it.name.lowercase() })
    }

    suspend fun removePaired(id: Int) = context.dataStore.edit { prefs ->
        prefs[K.paired] = PairedDevice.listToJson(PairedDevice.listFromJson(prefs[K.paired]).filterNot { it.id == id })
    }

    companion object {
        fun defaultPhoneName(): String {
            val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            return if (Build.MODEL.startsWith(maker, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
        }
    }
}
