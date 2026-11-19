package dev.astra.app.host

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** What the Share screen shows. Lives as long as the app process. */
data class HostState(
    val sharing: Boolean = false,
    /** off | connecting | online | error */
    val link: String = "off",
    val linkError: String? = null,
    val deviceId: Int? = null,
    val name: String = "",
    /** Who is controlling this phone right now, or null. */
    val session: String? = null,
    /** An Allow / Deny question waiting for an answer, or null. */
    val prompt: Approvals.Prompt? = null,
)

object HostRuntime {
    private val _state = MutableStateFlow(HostState())
    val state: StateFlow<HostState> = _state

    fun update(change: (HostState) -> HostState) = _state.update(change)

    /** The PIN shown on the Share screen. A new one each time sharing starts. */
    @Volatile var pins = PinManager()

    /** Set while a session is live: ends it from this phone ("End session"). */
    @Volatile var endSession: (() -> Unit)? = null
}
