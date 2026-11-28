package me.rerere.rikkahub.web

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.common.util.formatExceptionForLog
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

private const val TAG = "NsdServiceRegistrar"
private const val DEFAULT_SERVICE_TYPE = "_http._tcp.local."
const val DEFAULT_SERVICE_NAME = "rikkahub"

data class RegisteredServiceInfo(
    val serviceName: String,
    val hostname: String,
    val port: Int,
    val address: InetAddress
)

class NsdServiceRegistrar(
    private val context: Context
) {
    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    suspend fun register(
        port: Int,
        serviceName: String = DEFAULT_SERVICE_NAME,
        serviceType: String = DEFAULT_SERVICE_TYPE,
        onRegistered: ((RegisteredServiceInfo) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        if (jmdns != null) {
            unregister()
        }

        try {
            // Acquire multicast lock for mDNS
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("jmdns-lock")?.apply {
                setReferenceCounted(true)
                acquire()
            }

            val address = getLocalIpAddress()
            if (address == null) {
                Log.e(TAG, "Failed to get local IP address")
                cleanup()
                return@withContext
            }

            Log.i(TAG, "Creating JmDNS with hostname=$serviceName, address=$address")

            // Create JmDNS instance with custom hostname
            // This will register hostname.local -> IP address
            val mdns = JmDNS.create(address, serviceName)
            jmdns = mdns

            // Register HTTP service
            val serviceInfo = ServiceInfo.create(
                serviceType,
                serviceName,
                port,
                "RikkaHub Web Server"
            )
            mdns.registerService(serviceInfo)
            currentCoroutineContext().ensureActive()

            Log.i(
                TAG,
                "Service registered: $serviceName.$serviceType port=$port, hostname=$serviceName.local"
            )

            onRegistered?.invoke(
                RegisteredServiceInfo(
                    serviceName = serviceName,
                    hostname = "$serviceName.local",
                    port = port,
                    address = address
                )
            )
        } catch (e: CancellationException) {
            cleanup()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, formatExceptionForLog("Nsd.register", e))
            cleanup()
        }
    }

    suspend fun unregister() = withContext(Dispatchers.IO) {
        cleanup()
    }

    private fun cleanup() {
        val mdns = jmdns.also { jmdns = null }
        val lock = multicastLock.also { multicastLock = null }
        try {
            try {
                mdns?.unregisterAllServices()
            } finally {
                mdns?.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, formatExceptionForLog("Nsd.close", e))
        } finally {
            try {
                if (lock?.isHeld == true) lock.release()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, formatExceptionForLog("Nsd.releaseMulticastLock", e))
            }
        }

        Log.i(TAG, "Service unregistered")
    }

    private fun getLocalIpAddress(): InetAddress? {
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiInfo = wifiManager?.connectionInfo
            val ipInt = wifiInfo?.ipAddress ?: return null

            if (ipInt == 0) return null

            val ipBytes = byteArrayOf(
                (ipInt and 0xff).toByte(),
                (ipInt shr 8 and 0xff).toByte(),
                (ipInt shr 16 and 0xff).toByte(),
                (ipInt shr 24 and 0xff).toByte()
            )
            InetAddress.getByAddress(ipBytes)
        } catch (e: Exception) {
            Log.e(TAG, formatExceptionForLog("Nsd.resolveLocalAddress", e))
            null
        }
    }
}
