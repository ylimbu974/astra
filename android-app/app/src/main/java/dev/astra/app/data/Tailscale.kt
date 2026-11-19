package dev.astra.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * A1 check: "Tailscale: Connected". Android does not let apps read the Tailscale app's
 * state, so we look for what it creates: a VPN interface holding a 100.64.0.0/10 address.
 */
object Tailscale {
    const val PACKAGE = "com.tailscale.ipn"

    fun isConnected(): Boolean = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { nif ->
            nif.isUp && nif.inetAddresses.toList().any { addr ->
                addr is Inet4Address && addr.address.let { b ->
                    (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
                }
            }
        }
    } catch (e: Exception) {
        false
    }

    /** E2 "Open Tailscale app": the app if installed, otherwise its Play Store page. */
    fun open(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PACKAGE"))
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
    }
}
