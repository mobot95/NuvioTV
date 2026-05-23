package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.VlcHardwareDecodeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC

/**
 * Provides a shared instance of LibVLC configured according to app settings.
 * Re-initialized if core settings change to ensure the native instance
 * matches user preferences.
 */
object VlcInstanceProvider {
    private const val TAG = "VlcInstanceProvider"

    @Volatile
    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var libVLC: LibVLC? = null

    @Volatile
    private var initFailed = false

    fun get(context: Context, settings: PlayerSettings? = null): LibVLC {
        val current = libVLC
        if (current != null) return current

        if (initFailed) {
            throw IllegalStateException("LibVLC initialization previously failed. Cannot retry.")
        }

        return synchronized(this) {
            if (libVLC != null) return@synchronized libVLC!!
            if (initFailed) {
                throw IllegalStateException("LibVLC initialization previously failed. Cannot retry.")
            }
            try {
                createLibVLC(context, settings).also { libVLC = it }
            } catch (e: Exception) {
                initFailed = true
                Log.e(TAG, "LibVLC initialization failed, marking as failed state", e)
                throw e
            }
        }
    }

    private fun createLibVLC(context: Context, settings: PlayerSettings?): LibVLC {
        Log.d(TAG, "Creating new LibVLC instance with settings")
        val args = ArrayList<String>().apply {
            if (BuildConfig.DEBUG) {
                add("-vvv")
                add("--log-verbose=2")
            }

            if (settings != null) {
                // --- 1. Hardware Decoding Mapping ---
                when (settings.vlcHardwareDecodeMode) {
                    VlcHardwareDecodeMode.HARDWARE -> {
                        add("--codec=mediacodec_ndk,mediacodec_jni,none")
                    }
                    VlcHardwareDecodeMode.SOFTWARE -> {
                        add("--no-mediacodec")
                    }
                    VlcHardwareDecodeMode.AUTO -> {
                        // Default VLC behavior
                    }
                }

                // --- 2. Audio Signal Path (Passthrough vs. OpenSLES Safety) ---
                if (settings.tunnelingEnabled) {
                    add("--aout=android_audiotrack")
                    add("--audiotrack-passthrough")
                } else {
                    add("--aout=opensles") // Force OpenSLES to bypass DynamicsProcessing crashes
                }

                // --- 3. Audio Downmix ---
                if (settings.downmixEnabled) {
                    add("--stereo-mode=1")
                }

                // --- 4. Network Caching ---
                val networkCaching = settings.bufferSettings.minBufferMs.coerceIn(1500, 10000)
                add("--network-caching=$networkCaching")
                add("--file-caching=$networkCaching")
                add("--live-caching=$networkCaching")
                add("--sout-mux-caching=$networkCaching")
            } else {
                // Defaults if settings not provided (fallback)
                add("--aout=opensles")
                add("--network-caching=3000")
            }

            // Stability Defaults
            add("--audio-resampler=soxr")
            add("--no-audio-time-stretch")
            add("--drop-late-frames")
            add("--skip-frames")
        }
        return LibVLC(context.applicationContext, args)
    }

    /**
     * Pre-initializes LibVLC on a background thread.
     */
    fun preWarm(context: Context) {
        if (libVLC != null) return
        if (initFailed) return
        
        val currentScope = scope
        currentScope.launch {
            try {
                get(context, null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pre-warm LibVLC", e)
            }
        }
    }

    /**
     * Cleans up resources. 
     * Mandatory to call when changing core settings that require native re-init.
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up VlcInstanceProvider")
        val oldScope = scope
        oldScope.cancel()
        synchronized(this) {
            libVLC?.release()
            libVLC = null
            initFailed = false
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
    }
}
