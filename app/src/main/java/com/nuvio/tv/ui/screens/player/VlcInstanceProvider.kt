package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.util.Log
import com.nuvio.tv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC

/**
 * Provides a shared instance of LibVLC to avoid expensive re-initialization
 * which blocks the UI thread.
 */
object VlcInstanceProvider {
    private const val TAG = "VlcInstanceProvider"

    @Volatile
    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var libVLC: LibVLC? = null

    @Volatile
    private var initFailed = false

    fun get(context: Context): LibVLC {
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
                createLibVLC(context).also { libVLC = it }
            } catch (e: Exception) {
                initFailed = true
                Log.e(TAG, "LibVLC initialization failed, marking as failed state", e)
                throw e
            }
        }
    }

    private fun createLibVLC(context: Context): LibVLC {
        Log.d(TAG, "Creating new LibVLC instance")
        val args = ArrayList<String>().apply {
            // Only use verbose logging in debug builds to avoid performance impact in production
            if (BuildConfig.DEBUG) {
                add("-vvv")
            }
            add("--network-caching=1500")
            add("--file-caching=1500")
            add("--live-caching=1500")
            add("--sout-mux-caching=1500")

            // Performance options
            add("--drop-late-frames")
            add("--skip-frames")
        }
        return LibVLC(context.applicationContext, args)
    }

    /**
     * Pre-initializes LibVLC on a background thread to avoid blocking the UI
     * when the player is first needed.
     */
    fun preWarm(context: Context) {
        if (libVLC != null) return
        if (initFailed) {
            Log.w(TAG, "Skipping preWarm - initialization previously failed")
            return
        }
        val currentScope = scope
        currentScope.launch {
            try {
                get(context)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pre-warm LibVLC", e)
            }
        }
    }

    /**
     * Cleans up resources and cancels all pending coroutines.
     * Should be called when the app is shutting down to prevent coroutine leaks.
     *
     * Note: This method resets the initFailed flag to allow retry after cleanup.
     * This is intentional to support app restart scenarios where a temporary
     * initialization failure might be resolved after cleanup.
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
