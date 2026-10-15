package com.shilapi.xcertplay.fcewheel

import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import android.view.KeyEvent
import com.shilapi.xcertplay.CarPlayMediaKeys
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import java.io.File
import java.lang.reflect.Method

/**
 * CHANGAN (FCE) steering-wheel media keys, captured from the car's own input service.
 *
 * The head unit hands the wheel's next/previous keys to its FCE input service
 * (`fce_misc_service_input`) and, unlike BYD, produces no standard Android KeyEvent outside a
 * Bluetooth-audio source - so neither a media session nor CarPlayHostActivity's window sees them
 * there. That service's `registerListener` has no permission check, so we subscribe to the raw key
 * codes the same way the car's own apps do and forward them to the active CarPlay session.
 *
 * Two sentinel files in the app's external files directory control it, so either can be toggled
 * over adb without rebuilding the APK:
 *
 *  - `fce_wheel_keys.off` - disable this listener entirely; the upstream window route takes over.
 *  - `fce_wheel_keys.send_on_bt` - while the car's media source is `btaudio`, the head unit's own
 *    Bluetooth (AVRCP) control already drives the iPhone's music, so forwarding the media keys too
 *    would double-fire. The press is therefore handed back to the car by default, and this sentinel
 *    forces this listener to send it anyway - the escape hatch for a head unit whose own AVRCP does
 *    not take the wheel over. Confirmed on the T7 (inCall) head unit: with the hand-off off, a
 *    Bluetooth-source press moved two tracks instead of one.
 */
internal object FceWheelKeys {
    private const val TAG = "FceWheelKeys"

    /* FCE private services (taken from the FCEMiscService reverse engineering). */
    private const val SERVICE_INPUT = "fce_misc_service_input"
    private const val DESC_INPUT = "com.fce.misc.proxy.input.IMiscInput"
    private const val DESC_INPUT_LISTENER = "com.fce.misc.proxy.input.IMiscInputListener"
    private const val TX_REGISTER = 1
    private const val TX_ON_KEY_EVENT = 2

    private const val SERVICE_SOURCE = "fce_misc_service_source_msg"
    private const val DESC_SOURCE = "com.fce.misc.proxy.source.ISourceMsg"
    private const val TX_GET_REAL_SOURCE = 2

    /* The wheel's raw codes, as the head unit broadcasts them to its own input service. */
    private const val KEYCODE_NEXT = 87
    private const val KEYCODE_PREVIOUS = 88

    private const val SENTINEL_OFF = "fce_wheel_keys.off"
    private const val SENTINEL_SEND_ON_BT = "fce_wheel_keys.send_on_bt"

    /** The car's Bluetooth media source, which brings its own AVRCP control for the wheel. */
    private const val SOURCE_BLUETOOTH = "btaudio"

    private val RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)

    /**
     * Created lazily on first use. A field initialiser would run during class initialisation, which
     * fails unrecoverably on a thread with no main looper; every real call comes from an Activity.
     */
    private val handler: Handler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile private var appContext: Context? = null
    @Volatile private var registered = false
    private var attempts = 0
    private var listener: FceKeyBinder? = null

    /**
     * Whether this listener is the one handling the wheel, so the CarPlay window must leave the
     * press alone instead of sending it a second time.
     */
    fun handlesWheelKeys(): Boolean = registered && !offSentinelPresent()

    /** Registers with the car input service; safe to call repeatedly, retries while it comes up. */
    @Synchronized
    fun ensure(context: Context) {
        appContext = context.applicationContext
        if (registered) return
        if (offSentinelPresent()) {
            Log.i(TAG, "sentinel present -> listener disabled, the window route owns the wheel")
            return
        }
        val service = systemService(SERVICE_INPUT)
        if (service == null) {
            Log.i(TAG, "service $SERVICE_INPUT not found -> retry")
            scheduleRetry()
            return
        }
        if (register(service)) {
            registered = true
            attempts = 0
            Log.i(TAG, "registered as listener on $SERVICE_INPUT (ok)")
            watchServiceDeath(service)
        } else {
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (attempts >= RETRY_DELAYS_MS.size) {
            Log.w(TAG, "give up after $attempts attempts (service never came up)")
            return
        }
        val delay = RETRY_DELAYS_MS[attempts++]
        Log.i(TAG, "retry #$attempts scheduled in $delay ms")
        handler.postDelayed({ appContext?.let(::ensure) }, delay)
    }

    private fun watchServiceDeath(service: IBinder) {
        runCatching {
            service.linkToDeath({
                Log.w(TAG, "service died -> re-register")
                registered = false
                attempts = 0
                appContext?.let(::ensure)
            }, 0)
        }.onFailure { Log.w(TAG, "linkToDeath failed", it) }
    }

    /** Registers our binder as a listener (transaction 1). */
    private fun register(service: IBinder): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            val binder = listener ?: FceKeyBinder().also { listener = it }
            data.writeInterfaceToken(DESC_INPUT)
            data.writeStrongBinder(binder)
            service.transact(TX_REGISTER, data, reply, IBinder.FLAG_ONEWAY)
        } catch (t: Throwable) {
            Log.w(TAG, "register failed: $t")
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** `android.os.ServiceManager` is a hidden API, but the car registers its services there. */
    private fun systemService(name: String): IBinder? = try {
        val manager = Class.forName("android.os.ServiceManager")
        val getService: Method = manager.getMethod("getService", String::class.java)
        getService.invoke(null, name) as? IBinder
    } catch (t: Throwable) {
        Log.w(TAG, "ServiceManager.getService($name) failed: $t")
        null
    }

    /* ------------------------------------------------------------------ key handling */

    /** Equivalent to the service's IMiscInputListener.Stub; the car calls this for every key. */
    private class FceKeyBinder : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == TX_ON_KEY_EVENT) {
                data.enforceInterface(DESC_INPUT_LISTENER)
                val event = if (data.readInt() != 0) KeyEvent.CREATOR.createFromParcel(data) else null
                val display = data.readInt()
                runCatching { handle(event, display) }
                    .onFailure { Log.w(TAG, "handle threw", it) }
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    /** A press counts once, on the down; the release is dropped, as the car pairs them itself. */
    private fun handle(event: KeyEvent?, display: Int) {
        if (event == null || event.action != KeyEvent.ACTION_DOWN) return
        val index = when (event.keyCode) {
            KEYCODE_NEXT -> CarPlayMediaButton.NEXT
            KEYCODE_PREVIOUS -> CarPlayMediaButton.PREVIOUS
            else -> {
                Log.i(TAG, "wheel key ${event.keyCode} (display=$display) ignored")
                return
            }
        }
        if (leaveToCarBluetooth(event.keyCode)) return
        Log.i(TAG, "wheel key ${event.keyCode} (display=$display) -> CarPlay $index")
        CarPlayMediaKeys.onExternalMediaKey(index)
    }

    /**
     * While the car's source is Bluetooth, the head unit's own AVRCP already drives the iPhone, so
     * this press is left to it rather than sent twice. `fce_wheel_keys.send_on_bt` overrides that
     * and makes this listener send the press itself, for a head unit whose AVRCP leaves it alone.
     */
    private fun leaveToCarBluetooth(keyCode: Int): Boolean {
        if (sendOnBtSentinelPresent()) return false
        val source = realSource()
        if (source != SOURCE_BLUETOOTH) return false
        Log.i(TAG, "wheel key $keyCode -> left to car Bluetooth (realSource=$source)")
        return true
    }

    /** ISourceMsg.getRealSource(): the source the head unit counts as current ("btaudio", "app", ...). */
    private fun realSource(): String? {
        val service = systemService(SERVICE_SOURCE) ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESC_SOURCE)
            service.transact(TX_GET_REAL_SOURCE, data, reply, 0)
            reply.readException()
            reply.readString()
        } catch (t: Throwable) {
            Log.w(TAG, "realSource failed: $t")
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /* ------------------------------------------------------------------ sentinels */

    private fun sentinel(name: String): File? =
        appContext?.getExternalFilesDir(null)?.let { File(it, name) }

    private fun offSentinelPresent(): Boolean = sentinel(SENTINEL_OFF)?.exists() == true

    private fun sendOnBtSentinelPresent(): Boolean = sentinel(SENTINEL_SEND_ON_BT)?.exists() == true
}
