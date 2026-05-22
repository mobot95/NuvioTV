package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.view.View
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.interfaces.IVLCVout
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.math.pow

class NuvioVlcSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : VLCVideoLayout(context, attrs), IVLCVout.Callback, MediaPlayer.EventListener {

    private var libVlc: LibVLC? = null
    var mediaPlayer: MediaPlayer? = null
    private var surfacesCreated = false
    private var pendingPlay = false
    private var currentAspectMode: AspectMode = AspectMode.ORIGINAL
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

    private fun initializeMediaPlayerIfNeeded(): Boolean {
        if (mediaPlayerInitialized && mediaPlayer != null) return true

        return try {
            val vlcInstance = libVlc ?: VlcInstanceProvider.get(context).also { libVlc = it }

            mediaPlayer?.let { stalePlayer ->
                runCatching { stalePlayer.setEventListener(null) }
                runCatching { stalePlayer.vlcVout.removeCallback(this) }
                runCatching { stalePlayer.release() }
            }

            mediaPlayer = MediaPlayer(vlcInstance).apply {
                setEventListener(this@NuvioVlcSurfaceView)
                vlcVout.addCallback(this@NuvioVlcSurfaceView)
            }
            mediaPlayerInitialized = true
            isReleasing = false
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "[VLC] Failed to initialize MediaPlayer", e)
            mediaPlayer = null
            mediaPlayerInitialized = false
            false
        }
    }
    
    // MediaPlayer.EventListener implementation
    override fun onEvent(event: MediaPlayer.Event) {
        // Ignore events if release is in progress to prevent NPEs from callbacks already in flight
        if (isReleasing) return

        when (event.type) {
            MediaPlayer.Event.Playing -> {
                android.util.Log.d("NuvioVlcSurfaceView", "[VLC Event] Playing")
                onPlaybackStateChanged?.invoke(true)
            }
            MediaPlayer.Event.Paused -> {
                android.util.Log.d("NuvioVlcSurfaceView", "[VLC Event] Paused")
                onPlaybackStateChanged?.invoke(false)
            }
            MediaPlayer.Event.Stopped -> {
                android.util.Log.d("NuvioVlcSurfaceView", "[VLC Event] Stopped")
                onPlaybackStateChanged?.invoke(false)
            }
            MediaPlayer.Event.EndReached -> {
                android.util.Log.d("NuvioVlcSurfaceView", "[VLC Event] EndReached")
                onEndReached?.invoke()
            }
            MediaPlayer.Event.EncounteredError -> {
                android.util.Log.e("NuvioVlcSurfaceView", "[VLC Event] EncounteredError")
                onError?.invoke("VLC playback error")
            }
            MediaPlayer.Event.Buffering -> {
                val bufferPercent = event.buffering
                android.util.Log.d("NuvioVlcSurfaceView", "[VLC Event] Buffering: $bufferPercent%")
                onBufferingChanged?.invoke(bufferPercent)
            }
            MediaPlayer.Event.TimeChanged -> {
                val timeMs = event.timeChanged
                onTimeChanged?.invoke(timeMs)
            }
            MediaPlayer.Event.LengthChanged -> {
                val lengthMs = event.lengthChanged
                android.util.Log.d("NuvioVlcSurfaceView", "[VLC Event] LengthChanged: $lengthMs ms")
                onLengthChanged?.invoke(lengthMs)
            }
            MediaPlayer.Event.Vout -> {
                val voutCount = event.voutCount
                android.util.Log.d("NuvioVlcSurfaceView", "[VLC Event] Vout count: $voutCount")
                if (voutCount > 0) {
                    onFirstFrameRendered?.invoke()
                }
            }
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> {
                android.util.Log.d("NuvioVlcSurfaceView", "[VLC Event] Track changed: ${event.type}")
                onTracksChanged?.invoke()
            }
        }
    }

    fun attachMediaPlayer() {
        if (!initializeMediaPlayerIfNeeded()) {
            android.util.Log.w(TAG, "[VLC] attachMediaPlayer called but MediaPlayer initialization failed")
            return
        }
        // Reset isReleasing flag to handle view reuse scenarios
        isReleasing = false
        surfacesCreated = false
        mediaPlayer?.attachViews(this, null, true, false)
    }

    fun detachMediaPlayer() {
        if (!mediaPlayerInitialized) {
            android.util.Log.w(TAG, "[VLC] detachMediaPlayer called but MediaPlayer not initialized")
            return
        }
        mediaPlayer?.stop() // Following official example: stop before detachViews
        mediaPlayer?.detachViews()
        surfacesCreated = false
    }

    fun setMedia(url: String, headers: Map<String, String> = emptyMap()) {
        if (!initializeMediaPlayerIfNeeded()) {
            android.util.Log.w(TAG, "[VLC] setMedia called but MediaPlayer initialization failed")
            return
        }

        val uri = android.net.Uri.parse(url)
        val media = Media(libVlc, uri).apply {
            // Add headers
            headers.forEach { (key, value) ->
                addOption(":http-header=$key=$value")
            }
            // Enable hardware decoding
            addOption(":codec=mediacodec,all")
            // Set network caching
            addOption(":network-caching=1500")
        }
        mediaPlayer?.media = media
        media.release() // Following official example: release media after setMedia
    }

    fun setPaused(paused: Boolean) {
        if (paused) {
            pendingPlay = false
            mediaPlayer?.pause()
        } else {
            // Always try to play - VLC handles the surface internally
            pendingPlay = false
            mediaPlayer?.play()
        }
    }

    fun isPlayingNow(): Boolean {
        val isPlaying = mediaPlayer?.isPlaying == true
        return isPlaying
    }

    fun seekTo(positionMs: Long) {
        val mp = mediaPlayer ?: return
        val duration = mp.length
        android.util.Log.d(TAG, "[VLC] seekTo - positionMs=$positionMs currentPos=${mp.time} duration=$duration isSeekable=${mp.isSeekable}")
        
        if (!mp.isSeekable) {
            android.util.Log.w(TAG, "[VLC] seekTo - media is not seekable!")
            return
        }
        
        try {
            // Try using setPosition (0.0-1.0 range) which is more reliable
            if (duration > 0) {
                val position = positionMs.toFloat() / duration.toFloat()
                val clampedPosition = position.coerceIn(0f, 1f)
                mp.setPosition(clampedPosition)
                android.util.Log.d(TAG, "[VLC] seekTo - used setPosition($clampedPosition), newPos=${mp.time}")
            } else {
                // Fallback to setTime if duration is unknown
                mp.time = positionMs
                android.util.Log.d(TAG, "[VLC] seekTo - used time property, newPos=${mp.time}")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "[VLC] seekTo failed", e)
        }
    }

    fun getCurrentPosition(): Long {
        return mediaPlayer?.time ?: 0L
    }

    fun getDuration(): Long {
        return mediaPlayer?.length ?: 0L
    }

    fun setPlaybackSpeed(speed: Float) {
        mediaPlayer?.rate = speed
    }
    
    // Set subtitle delay in milliseconds (positive = delayed, negative = earlier)
    fun setSubtitleDelayMs(delayMs: Int) {
        android.util.Log.d("NuvioVlcSurfaceView", "[VLC] setSubtitleDelayMs - delayMs=$delayMs")
        try {
            // VLC uses microseconds for SPU delay
            val delayUs = delayMs.toLong() * 1000L
            mediaPlayer?.setSpuDelay(delayUs)
        } catch (e: Exception) {
            android.util.Log.e("NuvioVlcSurfaceView", "[VLC] setSubtitleDelayMs failed", e)
        }
    }
    
    // Get current subtitle delay in milliseconds
    fun getSubtitleDelayMs(): Int {
        return try {
            ((mediaPlayer?.spuDelay ?: 0L) / 1000L).toInt()
        } catch (e: Exception) {
            0
        }
    }
    
    // Set audio delay in milliseconds (positive = delayed, negative = earlier)
    fun setAudioDelayMs(delayMs: Int) {
        android.util.Log.d("NuvioVlcSurfaceView", "[VLC] setAudioDelayMs - delayMs=$delayMs")
        try {
            // VLC uses microseconds for audio delay
            val delayUs = delayMs.toLong() * 1000L
            mediaPlayer?.setAudioDelay(delayUs)
        } catch (e: Exception) {
            android.util.Log.e("NuvioVlcSurfaceView", "[VLC] setAudioDelayMs failed", e)
        }
    }
    
    // Get current audio delay in milliseconds
    fun getAudioDelayMs(): Int {
        return try {
            ((mediaPlayer?.audioDelay ?: 0L) / 1000L).toInt()
        } catch (e: Exception) {
            0
        }
    }
    
    // Set volume (0-100 is normal range, >100 for amplification)
    fun setVolume(volume: Int) {
        android.util.Log.d("NuvioVlcSurfaceView", "[VLC] setVolume - volume=$volume")
        try {
            mediaPlayer?.volume = volume.coerceIn(0, 200)
        } catch (e: Exception) {
            android.util.Log.e("NuvioVlcSurfaceView", "[VLC] setVolume failed", e)
        }
    }
    
    // Get current volume
    fun getVolume(): Int {
        return try {
            mediaPlayer?.volume ?: 100
        } catch (e: Exception) {
            100
        }
    }
    
    // Apply audio amplification in dB (similar to MPV implementation)
    fun applyAudioAmplificationDb(db: Int) {
        val clampedDb = db.coerceIn(0, 10)
        // Convert dB to linear scale and then to VLC volume (100 = normal)
        val linearScale = 10.0.pow(clampedDb / 20.0)
        val targetVolume = (100.0 * linearScale).toInt().coerceIn(0, 200)
        setVolume(targetVolume)
    }
    
    // Apply aspect mode
    fun applyAspectMode(mode: AspectMode) {
        currentAspectMode = mode
        android.util.Log.d(TAG, "[VLC] applyAspectMode - mode=$mode")
        try {
            when (mode) {
                AspectMode.ORIGINAL -> {
                    mediaPlayer?.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT)
                }
                AspectMode.FULL_SCREEN -> {
                    // Crop to fill screen (may cut edges)
                    mediaPlayer?.setVideoScale(MediaPlayer.ScaleType.SURFACE_FILL)
                }
                AspectMode.STRETCH -> {
                    // Stretch to fill (ignores aspect ratio)
                    // SURFACE_STRETCH not available in this VLC version, use SURFACE_FILL as fallback
                    mediaPlayer?.setVideoScale(MediaPlayer.ScaleType.SURFACE_FILL)
                }
                AspectMode.SLIGHT_ZOOM -> {
                    // Slight zoom - use 16:10 aspect
                    mediaPlayer?.setVideoScale(MediaPlayer.ScaleType.SURFACE_16_10)
                }
                AspectMode.CINEMA_ZOOM -> {
                    // Cinema zoom - use 16:9 crop
                    mediaPlayer?.setVideoScale(MediaPlayer.ScaleType.SURFACE_16_9)
                }
                AspectMode.VERTICAL_STRETCH -> {
                    // Fit height - use fit screen
                    mediaPlayer?.setVideoScale(MediaPlayer.ScaleType.SURFACE_FIT_SCREEN)
                }
                AspectMode.HORIZONTAL_STRETCH -> {
                    // Fit width - stretch to fill width
                    // SURFACE_FIT_WIDTH not available in this VLC version, use SURFACE_FILL as fallback
                    mediaPlayer?.setVideoScale(MediaPlayer.ScaleType.SURFACE_FILL)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "[VLC] applyAspectMode failed", e)
        }
    }
    
    // Apply audio language preferences - tries to select audio track matching preferred languages
    fun applyAudioLanguagePreferences(languages: List<String>) {
        if (languages.isEmpty()) {
            android.util.Log.d(TAG, "[VLC] applyAudioLanguagePreferences - empty list, skipping")
            return
        }
        android.util.Log.d(TAG, "[VLC] applyAudioLanguagePreferences - languages=$languages")
        try {
            val audioTracks = getAudioTracks() ?: return
            if (audioTracks.isEmpty()) {
                android.util.Log.d(TAG, "[VLC] applyAudioLanguagePreferences - no audio tracks available")
                return
            }
            
            // Try to find a track matching one of the preferred languages
            for (preferredLang in languages) {
                val normalizedPreferred = preferredLang.trim().lowercase()
                val matchingTrack = audioTracks.firstOrNull { track ->
                    val trackLang = track.language?.trim()?.lowercase() ?: return@firstOrNull false
                    trackLang == normalizedPreferred ||
                        trackLang.startsWith("$normalizedPreferred-") ||
                        trackLang.startsWith("${normalizedPreferred}_")
                }
                if (matchingTrack != null) {
                    val trackId = matchingTrack.id
                    if (trackId == null) {
                        android.util.Log.w(TAG, "[VLC] applyAudioLanguagePreferences - matching track has null ID, skipping")
                        continue
                    }
                    android.util.Log.d(TAG, "[VLC] applyAudioLanguagePreferences - selecting track: ${matchingTrack.language}/${matchingTrack.name}")
                    selectTrackById(trackId)
                    return
                }
            }
            android.util.Log.d(TAG, "[VLC] applyAudioLanguagePreferences - no matching track found for $languages")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "[VLC] applyAudioLanguagePreferences failed", e)
        }
    }
    
    // Add external subtitle file
    fun addExternalSubtitle(uri: Uri, select: Boolean = true): Boolean {
        android.util.Log.d("NuvioVlcSurfaceView", "[VLC] addExternalSubtitle - uri=$uri select=$select")
        return try {
            // Type 1 = subtitle slave
            mediaPlayer?.addSlave(IMedia.Slave.Type.Subtitle, uri, select) ?: false
        } catch (e: Exception) {
            android.util.Log.e("NuvioVlcSurfaceView", "[VLC] addExternalSubtitle failed", e)
            false
        }
    }
    
    // Add external subtitle from URL
    fun addExternalSubtitleUrl(url: String, select: Boolean = true): Boolean {
        return addExternalSubtitle(Uri.parse(url), select)
    }
    
    // Get video tracks
    fun getVideoTracks(): Array<IMedia.Track>? {
        return try {
            val tracks = mediaPlayer?.getTracks(TRACK_TYPE_VIDEO)
            android.util.Log.d(TAG, "[VLC] getVideoTracks - count=${tracks?.size ?: 0}")
            tracks
        } catch (e: Exception) {
            android.util.Log.e(TAG, "[VLC] getVideoTracks failed", e)
            null
        }
    }

    companion object {
        // Track type constants for libVLC 4.0 - use IMedia.Track.Type values
        // IMedia.Track.Type: Unknown=-1, Audio=0, Video=1, Text=2
        val TRACK_TYPE_AUDIO: Int get() = IMedia.Track.Type.Audio
        val TRACK_TYPE_VIDEO: Int get() = IMedia.Track.Type.Video
        val TRACK_TYPE_TEXT: Int get() = IMedia.Track.Type.Text  // Subtitles
        
        private const val TAG = "NuvioVlcSurfaceView"
    }

    // Get audio tracks using libVLC 4.0 API - returns typed array
    fun getAudioTracks(): Array<IMedia.Track>? {
        return try {
            val tracks = mediaPlayer?.getTracks(TRACK_TYPE_AUDIO)
            android.util.Log.d(TAG, "[VLC] getAudioTracks - count=${tracks?.size ?: 0}")
            tracks
        } catch (e: Exception) {
            android.util.Log.e(TAG, "[VLC] getAudioTracks failed", e)
            null
        }
    }

    // Get subtitle tracks using libVLC 4.0 API - returns typed array
    fun getSubtitleTracks(): Array<IMedia.Track>? {
        return try {
            val tracks = mediaPlayer?.getTracks(TRACK_TYPE_TEXT)
            android.util.Log.d(TAG, "[VLC] getSubtitleTracks - count=${tracks?.size ?: 0}")
            tracks
        } catch (e: Exception) {
            android.util.Log.e(TAG, "[VLC] getSubtitleTracks failed", e)
            null
        }
    }

    // Get selected audio track - returns typed track
    fun getSelectedAudioTrack(): IMedia.Track? {
        return try {
            mediaPlayer?.getSelectedTrack(TRACK_TYPE_AUDIO)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "[VLC] getSelectedAudioTrack failed", e)
            null
        }
    }

    // Get selected subtitle track - returns typed track
    fun getSelectedSubtitleTrack(): IMedia.Track? {
        return try {
            mediaPlayer?.getSelectedTrack(TRACK_TYPE_TEXT)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "[VLC] getSelectedSubtitleTrack failed", e)
            null
        }
    }

    // Select track by ID
    fun selectTrackById(trackId: String): Boolean {
        android.util.Log.d(TAG, "[VLC] selectTrackById - trackId=$trackId")
        return try {
            mediaPlayer?.selectTrack(trackId) ?: false
        } catch (e: Exception) {
            android.util.Log.e(TAG, "[VLC] selectTrackById failed", e)
            false
        }
    }

    // Select audio track by index
    fun selectAudioTrack(index: Int): Boolean {
        val tracks = getAudioTracks() ?: return false
        if (index < 0 || index >= tracks.size) return false
        val track = tracks[index]
        val trackId = track.id ?: run {
            android.util.Log.w(TAG, "[VLC] selectAudioTrack - track.id is null for index=$index")
            return false
        }
        return selectTrackById(trackId)
    }

    // Select subtitle track by index
    fun selectSubtitleTrack(index: Int): Boolean {
        val tracks = getSubtitleTracks() ?: return false
        if (index < 0 || index >= tracks.size) return false
        val track = tracks[index]
        val trackId = track.id ?: run {
            android.util.Log.w(TAG, "[VLC] selectSubtitleTrack - track.id is null for index=$index")
            return false
        }
        return selectTrackById(trackId)
    }

    // Disable subtitles
    fun disableSubtitles() {
        android.util.Log.d(TAG, "[VLC] disableSubtitles")
        try {
            mediaPlayer?.unselectTrackType(TRACK_TYPE_TEXT)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "[VLC] disableSubtitles failed", e)
        }
    }
    
    // Check if media is seekable
    fun isSeekable(): Boolean {
        return try {
            mediaPlayer?.isSeekable ?: false
        } catch (e: Exception) {
            false
        }
    }
    
    // Get player state
    fun getPlayerState(): Int {
        return try {
            mediaPlayer?.playerState ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    // Debug: log available methods on MediaPlayer for track APIs
    fun logAvailableMethods() {
        mediaPlayer?.let { mp ->
            val methods = mp.javaClass.methods
            val trackMethods = methods.filter { 
                it.name.contains("track", ignoreCase = true) || 
                it.name.contains("audio", ignoreCase = true) ||
                it.name.contains("spu", ignoreCase = true) ||
                it.name.contains("subtitle", ignoreCase = true)
            }
            trackMethods.forEach { method ->
                android.util.Log.d(TAG, "[VLC API] ${method.name}(${method.parameterTypes.joinToString { it.simpleName }}): ${method.returnType.simpleName}")
            }
        }
    }

    fun release() {
        if (isReleasing && mediaPlayer == null) {
            return
        }

        android.util.Log.d(TAG, "[VLC] release START")
        isReleasing = true

        // Clear all callbacks
        onPlaybackStateChanged = null
        onBufferingChanged = null
        onTimeChanged = null
        onLengthChanged = null
        onEndReached = null
        onError = null
        onFirstFrameRendered = null
        onTracksChanged = null

        if (mediaPlayerInitialized && mediaPlayer != null) {
            try {
                mediaPlayer?.setEventListener(null)
                mediaPlayer?.vlcVout?.removeCallback(this)
                mediaPlayer?.stop()
                mediaPlayer?.detachViews()
                mediaPlayer?.release()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "[VLC] release mediaPlayer failed", e)
            }
        }

        mediaPlayer = null
        mediaPlayerInitialized = false
        surfacesCreated = false
        android.util.Log.d(TAG, "[VLC] release COMPLETE")
    }

    // IVLCVout.Callback implementation
    override fun onSurfacesCreated(vout: IVLCVout?) {
        android.util.Log.d("NuvioVlcSurfaceView", "[VLC] onSurfacesCreated")
        surfacesCreated = true
        if (pendingPlay) {
            pendingPlay = false
            mediaPlayer?.play()
        }
    }

    override fun onSurfacesDestroyed(vout: IVLCVout?) {
        android.util.Log.d("NuvioVlcSurfaceView", "[VLC] onSurfacesDestroyed")
        surfacesCreated = false
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }
}
