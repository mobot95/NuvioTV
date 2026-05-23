package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import com.nuvio.tv.data.local.VlcHardwareDecodeMode
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.math.pow

/**
 * Custom VLC Video Layout for LibVLC 3.7.0 Stable.
 * Handles surface management, lifecycle, audio focus, and provides a clean API for the PlayerRuntimeController.
 */
class NuvioVlcSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : VLCVideoLayout(context, attrs), IVLCVout.Callback, MediaPlayer.EventListener {

    private var libVlc: LibVLC? = null
    var mediaPlayer: MediaPlayer? = null
    private var surfacesCreated = false
    private var pendingPlay = false
    private var currentAspectMode: AspectMode = AspectMode.ORIGINAL
    private var hardwareDecodeMode: VlcHardwareDecodeMode = VlcHardwareDecodeMode.AUTO
    
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "Audio focus lost, pausing player")
                setPaused(paused = true)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus gained")
            }
        }
    }
    
    @Volatile
    private var mediaPlayerInitialized = false
    @Volatile
    private var isReleasing = false
    
    // Event callbacks for PlayerRuntimeController
    var onPlaybackStateChanged: ((isPlaying: Boolean) -> Unit)? = null
    var onBufferingChanged: ((buffering: Float) -> Unit)? = null
    var onTimeChanged: ((timeMs: Long) -> Unit)? = null
    var onLengthChanged: ((lengthMs: Long) -> Unit)? = null
    var onEndReached: (() -> Unit)? = null
    var onError: ((message: String?) -> Unit)? = null
    var onFirstFrameRendered: (() -> Unit)? = null
    var onTracksChanged: (() -> Unit)? = null

    init {
        isReleasing = false
        initializeMediaPlayerIfNeeded()
    }

    /**
     * Ensures the MediaPlayer instance is created and listeners are attached.
     */
    private fun initializeMediaPlayerIfNeeded(settings: com.nuvio.tv.data.local.PlayerSettings? = null): Boolean {
        if (mediaPlayerInitialized && (mediaPlayer != null)) return true

        return try {
            val vlcInstance = libVlc ?: VlcInstanceProvider.get(context, settings).also { libVlc = it }

            mediaPlayer?.let { stalePlayer ->
                // CRITICAL FIX: Offload native release to background thread to prevent UI deadlock
                val playerToRelease = stalePlayer
                runCatching { playerToRelease.setEventListener(null) }
                runCatching { playerToRelease.vlcVout.removeCallback(this@NuvioVlcSurfaceView) }
                
                Thread {
                    Log.d(TAG, "[VLC] Releasing stale player in background")
                    runCatching {
                        if (playerToRelease.isPlaying) playerToRelease.stop()
                        playerToRelease.detachViews()
                        playerToRelease.release()
                    }
                    Log.d(TAG, "[VLC] Stale player released")
                }.start()
            }

            mediaPlayer = MediaPlayer(vlcInstance).apply {
                // Remove setAudioOutput override to allow global OpenSLES preference to take effect
                volume = 100
                setEventListener(this@NuvioVlcSurfaceView)
                vlcVout.addCallback(this@NuvioVlcSurfaceView)
            }
            mediaPlayerInitialized = true
            isReleasing = false
            true
        } catch (e: Exception) {
            Log.e(TAG, "[VLC] Failed to initialize MediaPlayer", e)
            mediaPlayer = null
            mediaPlayerInitialized = false
            false
        }
    }
    
    /**
     * MediaPlayer.EventListener implementation for LibVLC 3.x.
     */
    override fun onEvent(event: MediaPlayer.Event) {
        if (isReleasing) return

        when (event.type) {
            MediaPlayer.Event.Playing -> {
                Log.d(TAG, "[VLC Event] Playing. Audio Track: ${mediaPlayer?.audioTrack}, Volume: ${mediaPlayer?.volume}")
                onPlaybackStateChanged?.invoke(true)
            }
            MediaPlayer.Event.Paused -> {
                Log.d(TAG, "[VLC Event] Paused")
                onPlaybackStateChanged?.invoke(false)
            }
            MediaPlayer.Event.Stopped -> {
                Log.d(TAG, "[VLC Event] Stopped")
                onPlaybackStateChanged?.invoke(false)
            }
            MediaPlayer.Event.EndReached -> {
                Log.d(TAG, "[VLC Event] EndReached")
                onEndReached?.invoke()
            }
            MediaPlayer.Event.EncounteredError -> {
                Log.e(TAG, "[VLC Event] EncounteredError")
                val mp = mediaPlayer
                val errorMsg = when {
                    mp == null -> "Media player not initialized"
                    !mp.isSeekable -> "Stream is not seekable"
                    mp.length == 0L -> "Invalid media format or empty stream"
                    else -> "VLC playback error (event: ${event.type})"
                }
                onError?.invoke(errorMsg)
            }
            MediaPlayer.Event.Buffering -> {
                val bufferPercent = event.buffering
                onBufferingChanged?.invoke(bufferPercent)
            }
            MediaPlayer.Event.TimeChanged -> {
                onTimeChanged?.invoke(event.timeChanged)
            }
            MediaPlayer.Event.LengthChanged -> {
                Log.d(TAG, "[VLC Event] LengthChanged: ${event.lengthChanged} ms")
                onLengthChanged?.invoke(event.lengthChanged)
            }
            MediaPlayer.Event.Vout -> {
                if (event.voutCount > 0) {
                    Log.d(TAG, "[VLC Event] First frame rendered")
                    onFirstFrameRendered?.invoke()
                }
            }
            // Track changes in 3.x are signaled by these events
            MediaPlayer.Event.ESAdded, 
            MediaPlayer.Event.ESDeleted, 
            MediaPlayer.Event.ESSelected -> {
                Log.d(TAG, "[VLC Event] Tracks changed")
                onTracksChanged?.invoke()
            }
        }
    }

    /**
     * Attaches the MediaPlayer to this VideoLayout.
     */
    fun attachMediaPlayer(settings: com.nuvio.tv.data.local.PlayerSettings? = null) {
        if (!initializeMediaPlayerIfNeeded(settings)) return
        isReleasing = false
        // In 3.x, attachViews(VLCVideoLayout, videoHelper, useTextureView, useVideoLayout)
        mediaPlayer?.attachViews(this, null, true, false)
    }

    /**
     * Detaches the MediaPlayer and stops playback.
     */
    fun detachMediaPlayer() {
        if (!mediaPlayerInitialized) return
        mediaPlayer?.stop()
        mediaPlayer?.detachViews()
        surfacesCreated = false
    }

    /**
     * Configures the media to play, including HTTP headers and HW acceleration.
     */
    fun setMedia(url: String, headers: Map<String, String> = emptyMap()) {
        if (!initializeMediaPlayerIfNeeded()) return

        val media = Media(libVlc, Uri.parse(url)).apply {
            // FIX: Inject User-Agent for CDN compatibility
            if (!headers.any { it.key.equals("User-Agent", ignoreCase = true) }) {
                addOption(":http-user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            }
            
            // FIX: Correct HTTP header format "Key: Value"
            headers.forEach { (key, value) ->
                addOption(":http-header=$key: $value")
            }
            
            // Audio stabilization options
            addOption(":stereo-mode=1")
            
            // Respect hardware decode mode setting
            when (hardwareDecodeMode) {
                VlcHardwareDecodeMode.AUTO -> setHWDecoderEnabled(true, true)
                VlcHardwareDecodeMode.HARDWARE -> setHWDecoderEnabled(true, false)
                VlcHardwareDecodeMode.SOFTWARE -> setHWDecoderEnabled(false, false)
            }
            
            addOption(":network-caching=1500")
            addOption(":clock-jitter=0")
            addOption(":clock-synchro=0")
        }
        
        Log.d(TAG, "[VLC] Setting media URL: $url")
        mediaPlayer?.media = media
        media.release()
    }

    fun applyHardwareDecodeMode(mode: VlcHardwareDecodeMode) {
        hardwareDecodeMode = mode
    }

    /**
     * Handles Play/Pause requests with race condition protection and audio focus.
     */
    fun setPaused(paused: Boolean) {
        if (paused) {
            pendingPlay = false
            mediaPlayer?.pause()
            abandonAudioFocus()
        } else {
            if (requestAudioFocus()) {
                if (surfacesCreated) {
                    pendingPlay = false
                    mediaPlayer?.play()
                } else {
                    // FIX: If surface is not ready, defer play until onSurfacesCreated
                    pendingPlay = true
                    Log.d(TAG, "[VLC] Play deferred: surface not ready")
                }
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            
            audioFocusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    fun isPlayingNow(): Boolean = mediaPlayer?.isPlaying == true

    fun seekTo(positionMs: Long) {
        val mp = mediaPlayer ?: return
        val duration = mp.length
        
        if (!mp.isSeekable) return
        
        try {
            if (duration > 0) {
                val position = positionMs.toFloat() / duration.toFloat()
                mp.position = position.coerceIn(0f, 1f)
            } else {
                mp.time = positionMs
            }
        } catch (e: Exception) {
            Log.e(TAG, "[VLC] seekTo failed", e)
        }
    }

    fun getCurrentPosition(): Long = mediaPlayer?.time ?: 0L

    fun getDuration(): Long = mediaPlayer?.length ?: 0L

    fun setPlaybackSpeed(speed: Float) {
        mediaPlayer?.rate = speed
    }
    
    fun setSubtitleDelayMs(delayMs: Int) {
        try {
            // VLC uses microseconds
            mediaPlayer?.spuDelay = delayMs.toLong() * 1000L
        } catch (e: Exception) {
            Log.e(TAG, "setSubtitleDelayMs failed", e)
        }
    }
    
    fun applySubtitleStyle() {
        // LibVLC 3.x has limited subtitle styling support
        // Most styling is handled by LibVLC's native renderer
        // For advanced styling, use MPV engine instead
        Log.d(TAG, "[VLC] Subtitle styling limited in LibVLC 3.x - consider using MPV for advanced styling")
    }
    
    fun setAudioDelayMs(delayMs: Int) {
        try {
            mediaPlayer?.audioDelay = delayMs.toLong() * 1000L
        } catch (e: Exception) {
            Log.e(TAG, "setAudioDelayMs failed", e)
        }
    }
    
    fun applyAudioAmplificationDb(db: Int) {
        val clampedDb = db.coerceIn(0, 10)
        val linearScale = 10.0.pow(clampedDb / 20.0)
        val targetVolume = (100.0 * linearScale).toInt().coerceIn(0, 200)
        mediaPlayer?.volume = targetVolume
    }
    
    fun applyAspectMode(mode: AspectMode) {
        currentAspectMode = mode
        try {
            when (mode) {
                AspectMode.ORIGINAL -> {
                    mediaPlayer?.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
                }
                AspectMode.FULL_SCREEN -> {
                    mediaPlayer?.videoScale = MediaPlayer.ScaleType.SURFACE_FILL
                }
                AspectMode.STRETCH -> {
                    // Use FIT_SCREEN for stretch to fill entire screen
                    mediaPlayer?.videoScale = MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
                }
                AspectMode.SLIGHT_ZOOM -> {
                    // Use 16:10 for slight zoom (most common aspect ratio)
                    mediaPlayer?.videoScale = MediaPlayer.ScaleType.SURFACE_16_10
                }
                AspectMode.CINEMA_ZOOM -> {
                    // Use 4:3 for cinema zoom (wider crop)
                    mediaPlayer?.videoScale = MediaPlayer.ScaleType.SURFACE_4_3
                }
                AspectMode.VERTICAL_STRETCH -> {
                    // Use 16:10 for vertical stretch
                    mediaPlayer?.videoScale = MediaPlayer.ScaleType.SURFACE_16_10
                }
                AspectMode.HORIZONTAL_STRETCH -> {
                    // Use FILL for horizontal stretch
                    mediaPlayer?.videoScale = MediaPlayer.ScaleType.SURFACE_FILL
                }
            }
            Log.d(TAG, "[VLC] Applied aspect mode: $mode -> ${mediaPlayer?.videoScale}")
        } catch (e: Exception) {
            Log.e(TAG, "applyAspectMode failed", e)
        }
    }
    
    fun applyAudioLanguagePreferences(languages: List<String>) {
        try {
            val audioTracks = mediaPlayer?.audioTracks ?: return
            
            // Try to select preferred language track
            if (languages.isNotEmpty()) {
                for (preferredLang in languages) {
                    val normalizedPreferred = preferredLang.trim().lowercase()
                    val matchingTrack = audioTracks.firstOrNull { track ->
                        val trackName = track.name?.lowercase() ?: ""
                        trackName.contains(normalizedPreferred)
                    }
                    if (matchingTrack != null) {
                        mediaPlayer?.audioTrack = matchingTrack.id
                        Log.d(TAG, "[VLC] Selected audio track by language: ${matchingTrack.name} (id=${matchingTrack.id})")
                        return
                    }
                }
            }
            
            // FALLBACK: Select first available audio track if no match found
            val firstTrack = audioTracks.firstOrNull { it.id != -1 }
            if (firstTrack != null) {
                mediaPlayer?.audioTrack = firstTrack.id
                Log.d(TAG, "[VLC] Selected first audio track as fallback: ${firstTrack.name} (id=${firstTrack.id})")
            } else {
                Log.w(TAG, "[VLC] No valid audio tracks available")
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyAudioLanguagePreferences failed", e)
        }
    }
    
    fun addExternalSubtitleUrl(url: String, select: Boolean = true): Boolean {
        return try {
            // Type 1 = subtitle slave
            mediaPlayer?.addSlave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle, Uri.parse(url), select) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "addExternalSubtitleUrl failed", e)
            false
        }
    }

    // --- Track Management (3.x APIs) ---

    fun getAudioTracks(): Array<MediaPlayer.TrackDescription>? = mediaPlayer?.audioTracks

    fun getSubtitleTracks(): Array<MediaPlayer.TrackDescription>? = mediaPlayer?.spuTracks

    fun getSelectedAudioTrackId(): Int = mediaPlayer?.audioTrack ?: -1

    fun getSelectedSubtitleTrackId(): Int = mediaPlayer?.spuTrack ?: -1
    
    /**
     * Get detailed track information using TrackDescription API.
     * LibVLC 3.x has limited metadata - only id and name are available.
     * For full metadata (codec, channels, sample rate), use MPV engine.
     */
    fun getDetailedTracks(): VlcTrackSnapshot {
        val vlcAudioTracks = mediaPlayer?.audioTracks ?: emptyArray()
        val vlcSubtitleTracks = mediaPlayer?.spuTracks ?: emptyArray()
        val selectedAudioId = mediaPlayer?.audioTrack ?: -1
        val selectedSubId = mediaPlayer?.spuTrack ?: -1
        
        Log.d(TAG, "[VLC] getDetailedTracks: audio=${vlcAudioTracks.size}, selectedAudio=$selectedAudioId")
        
        try {
            val audioTracks = vlcAudioTracks
                .filter { it.id != -1 }
                .mapIndexed { index, track ->
                    VlcTrack(
                        id = track.id,
                        name = track.name ?: "Audio ${index + 1}",
                        language = null,
                        codec = null,
                        channelCount = null,
                        sampleRate = null,
                        isSelected = track.id == selectedAudioId,
                        isForced = false
                    )
                }
            
            val subtitleTracks = vlcSubtitleTracks
                .filter { it.id != -1 }
                .mapIndexed { index, track ->
                    val trackName = track.name ?: ""
                    val isForced = trackName.contains("forced", ignoreCase = true) ||
                            (trackName.contains("songs", ignoreCase = true) && trackName.contains("sign", ignoreCase = true))
                    
                    VlcTrack(
                        id = track.id,
                        name = trackName.takeIf { it.isNotBlank() } ?: "Subtitle ${index + 1}",
                        language = null,
                        codec = null,
                        isSelected = track.id == selectedSubId,
                        isForced = isForced
                    )
                }
            
            return VlcTrackSnapshot(audioTracks, subtitleTracks)
        } catch (e: Exception) {
            Log.e(TAG, "[VLC] getDetailedTracks failed", e)
            return VlcTrackSnapshot(emptyList(), emptyList())
        }
    }

    fun selectAudioTrack(id: Int): Boolean {
        mediaPlayer?.audioTrack = id
        return true
    }

    fun selectSubtitleTrack(id: Int): Boolean {
        mediaPlayer?.spuTrack = id
        return true
    }

    fun disableSubtitles() {
        mediaPlayer?.spuTrack = -1
    }

    /**
     * Clear UI listeners and audio focus immediately.
     * Native teardown is handled via [performNativeRelease].
     */
    fun release() {
        if (isReleasing && (mediaPlayer == null)) return

        Log.d(TAG, "[VLC] release sequence START")
        isReleasing = true

        onPlaybackStateChanged = null
        onBufferingChanged = null
        onTimeChanged = null
        onLengthChanged = null
        onEndReached = null
        onError = null
        onFirstFrameRendered = null
        onTracksChanged = null
        
        abandonAudioFocus()

        mediaPlayer?.let { mp ->
            runCatching { mp.setEventListener(null) }
            runCatching { mp.vlcVout.removeCallback(this) }
        }
    }

    /**
     * Performs blocking native release calls.
     * MUST be called on a background thread to prevent deadlocks.
     */
    fun performNativeRelease() {
        val mp = mediaPlayer
        mediaPlayer = null
        mediaPlayerInitialized = false
        surfacesCreated = false

        mp?.let { player ->
            runCatching {
                player.setEventListener(null)
                player.vlcVout.removeCallback(this)
                if (player.isPlaying) player.stop()
                player.detachViews()
                player.release()
            }
        }
    }

    // IVLCVout.Callback implementation
    override fun onSurfacesCreated(vout: IVLCVout?) {
        Log.d(TAG, "onSurfacesCreated")
        surfacesCreated = true
        if (pendingPlay) {
            pendingPlay = false
            mediaPlayer?.play()
        }
    }

    override fun onSurfacesDestroyed(vout: IVLCVout?) {
        Log.d(TAG, "onSurfacesDestroyed")
        surfacesCreated = false
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }

    companion object {
        private const val TAG = "NuvioVlcSurfaceView"
        private const val SUBTITLE_VERTICAL_OFFSET_MIN = -20
        private const val SUBTITLE_VERTICAL_OFFSET_MAX = 50
    }
}

data class VlcTrackSnapshot(
    val audioTracks: List<VlcTrack>,
    val subtitleTracks: List<VlcTrack>
)

data class VlcTrack(
    val id: Int,
    val name: String,
    val language: String?,
    val codec: String?,
    val channelCount: Int? = null,
    val sampleRate: Int? = null,
    val isForced: Boolean = false,
    val isSelected: Boolean = false
)
