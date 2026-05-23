package com.nuvio.tv.ui.screens.player

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean

internal fun PlayerRuntimeController.attachVlcView(view: NuvioVlcSurfaceView?) {
    if (vlcView === view) {
        return
    }

    // Clear callbacks from old view
    vlcView?.let { oldView ->
        oldView.onPlaybackStateChanged = null
        oldView.onBufferingChanged = null
        oldView.onTimeChanged = null
        oldView.onLengthChanged = null
        oldView.onEndReached = null
        oldView.onError = null
        oldView.onFirstFrameRendered = null
        oldView.onTracksChanged = null
    }

    vlcView = view

    // Setup event callbacks on new view
    view?.let { setupVlcEventCallbacks(it) }
}

private fun PlayerRuntimeController.setupVlcEventCallbacks(view: NuvioVlcSurfaceView) {
    var lastBufferingPercentBucket = -1

    view.onPlaybackStateChanged = { isPlaying ->
        Log.d(PlayerRuntimeController.TAG, "[VLC] onPlaybackStateChanged: isPlaying=$isPlaying")
        _uiState.update { state ->
            state.copy(
                isPlaying = isPlaying,
                isBuffering = if (isPlaying) false else state.isBuffering
            )
        }
        if (isPlaying && !hasRenderedFirstFrame) {
            hasRenderedFirstFrame = true
            _uiState.update { it.copy(showLoadingOverlay = false) }
        }
    }

    view.onBufferingChanged = { bufferPercent ->
        val normalizedPercent = bufferPercent.coerceIn(0f, 100f)
        val percentBucket = normalizedPercent.toInt()
        val isBuffering = normalizedPercent < 100f
        if ((percentBucket != lastBufferingPercentBucket) || (_uiState.value.isBuffering != isBuffering)) {
            lastBufferingPercentBucket = percentBucket
            _uiState.update { state ->
                state.copy(
                    isBuffering = isBuffering,
                    loadingProgress = normalizedPercent / 100f
                )
            }
        }
    }

    view.onTimeChanged = { timeMs ->
        if (pendingPreviewSeekPosition == null) {
            val duration = view.getDuration()
            if (duration > 0) {
                updatePlaybackTimeline(currentPosition = timeMs, duration = duration)
            }
        }
    }

    view.onLengthChanged = { lengthMs ->
        if (lengthMs > lastKnownDuration) {
            lastKnownDuration = lengthMs
        }
        updatePlaybackTimeline(duration = lengthMs)

        applyPendingVlcSeekIfNeeded(view, durationMs = lengthMs)

        // Update tracks and apply preferences in a single coroutine to avoid race conditions
        enqueueVlcTrackRefresh { 
            // Update tracks when duration is known (media is parsed)
            updateVlcAvailableTracks()

            // Apply persisted track preferences and auto-select subtitles
            val state = _uiState.value
            applyPersistedTrackPreference(
                audioTracks = state.audioTracks,
                subtitleTracks = state.subtitleTracks
            )
            tryAutoSelectPreferredSubtitleFromAvailableTracks()
        }
    }

    view.onEndReached = {
        Log.d(PlayerRuntimeController.TAG, "[VLC] onEndReached")
        _uiState.update { it.copy(playbackEnded = true, isPlaying = false) }
        emitCompletionScrobbleStop(progressPercent = 99.5f)
        saveWatchProgress()
        resetPostPlayStateAfterPlaybackEnded()
    }

    view.onError = { message ->
        Log.e(PlayerRuntimeController.TAG, "[VLC] onError: $message")
        _uiState.update { state ->
            state.copy(
                error = message ?: context.getString(com.nuvio.tv.R.string.player_error_mpv_playback_failed),
                showLoadingOverlay = false,
                isBuffering = false
            )
        }
    }

    view.onFirstFrameRendered = {
        Log.d(PlayerRuntimeController.TAG, "[VLC] onFirstFrameRendered")
        hasRenderedFirstFrame = true
        applyPendingVlcSeekIfNeeded(view)
        _uiState.update { state ->
            state.copy(
                showLoadingOverlay = false,
                isBuffering = false,
                loadingProgress = if (state.loadingProgress != null) 1f else null
            )
        }
    }

    view.onTracksChanged = {
        Log.d(PlayerRuntimeController.TAG, "[VLC] onTracksChanged")
        // Launch coroutine to avoid race conditions with onLengthChanged track updates
        enqueueVlcTrackRefresh {
            updateVlcAvailableTracks()
        }
    }
}

private var vlcTrackRefreshInProgress = false
private val vlcSeekInProgress = AtomicBoolean(false)
private val isVlcReleasing = AtomicBoolean(false)

private fun PlayerRuntimeController.enqueueVlcTrackRefresh(
    block: suspend () -> Unit
) {
    if (vlcTrackRefreshInProgress) {
        return  // Skip if already refreshing to prevent excessive track parsing
    }
    
    vlcTrackRefreshJob?.cancel()
    vlcTrackRefreshJob = scope.launch {
        vlcTrackRefreshInProgress = true
        try {
            block()
        } finally {
            vlcTrackRefreshInProgress = false
        }
    }
}

internal suspend fun PlayerRuntimeController.initializeVlcPlayer(
    url: String,
    headers: Map<String, String>,
    allowEngineFailover: Boolean = true,
    startPaused: Boolean = false
) {
    // Release ExoPlayer resources
    _exoPlayer?.release()
    _exoPlayer = null
    trackSelector = null
    try {
        currentMediaSession?.release()
    } catch (_: Exception) {}
    currentMediaSession = null
    notifyAudioSessionUpdate(false)

    val view = vlcView
    if (view == null) {
        _uiState.update {
            it.copy(
                isBuffering = true,
                isPlaying = false,
                showLoadingOverlay = it.loadingOverlayEnabled,
                error = null
            )
        }
        return
    }

    runCatching {
        // Setup event callbacks (in case view was attached before engine was set)
        setupVlcEventCallbacks(view)

        // Yield to allow UI updates before heavy operations
        yield()

        // Apply hardware decode mode
        view.applyHardwareDecodeMode(vlcHardwareDecodeModeSetting)

        val playerSettings = playerSettingsDataStore.playerSettings.first()

        // Following the official example: attachViews() BEFORE setMedia()
        view.attachMediaPlayer(playerSettings)
        view.setMedia(url, headers)

        // Apply playback settings
        view.setPlaybackSpeed(_uiState.value.playbackSpeed)
        view.applyAudioAmplificationDb(_uiState.value.audioAmplificationDb)
        view.setSubtitleDelayMs(_uiState.value.subtitleDelayMs)
        view.setAudioDelayMs(_uiState.value.audioDelayMs)
        view.applyAspectMode(_uiState.value.aspectMode)
        // Note: Subtitle styling not supported in LibVLC 3.x, use MPV for advanced styling

        // Apply preferred audio language preferences
        view.applyAudioLanguagePreferences(preferredAudioLanguages)

        // Create MediaSession for lock screen controls and Android Auto
        try {
            currentMediaSession?.release()
            currentMediaSession = null
            // Note: VLC doesn't integrate directly with MediaSession like ExoPlayer
            // For full MediaSession support, we would need to create a custom
            // MediaSession.Callback that bridges VLC events to MediaSession commands
            // For now, this is a placeholder for future implementation
        } catch (e: Exception) {
            Log.w(PlayerRuntimeController.TAG, "[VLC] MediaSession creation skipped: ${e.message}")
        }

        // Start playback
        view.setPaused(startPaused)

        hasRenderedFirstFrame = false
        _uiState.update {
            it.copy(
                isBuffering = true,
                isPlaying = !startPaused,
                showLoadingOverlay = it.loadingOverlayEnabled,
                error = null,
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                selectedAudioTrackIndex = -1,
                selectedSubtitleTrackIndex = -1
            )
        }
        cancelPauseOverlay()

        // Event-based updates are handled by callbacks, but we still need progress updates for skip intervals etc.
        startProgressUpdates()
        startWatchProgressSaving()
        scheduleHideControls()
        emitScrobbleStart()
    }.onFailure { error ->
        Log.e(PlayerRuntimeController.TAG, "libVLC initialize failed: ${error.message}", error)
        val detailedError = error.message ?: context.getString(com.nuvio.tv.R.string.player_error_mpv_playback_failed)
        if (
            maybeAutoSwitchInternalPlayerOnStartupError(
                detailedError = detailedError,
                allowEngineFailover = allowEngineFailover
            )
        ) {
            return@onFailure
        }
        _uiState.update {
            it.copy(
                error = detailedError,
                showLoadingOverlay = false,
                isBuffering = false
            )
        }
    }
}

internal fun PlayerRuntimeController.releaseVlcPlayer() {
    // Idempotency Check: Exit if already releasing to prevent native deadlocks
    if (!isVlcReleasing.compareAndSet(false, true)) {
        Log.d(PlayerRuntimeController.TAG, "[VLC] releaseVlcPlayer: already in progress, ignoring.")
        return
    }

    Log.d(PlayerRuntimeController.TAG, "[VLC] release sequence START")
    vlcTrackRefreshJob?.cancel()
    vlcTrackRefreshJob = null

    // Capture the view and clear UI reference immediately on Main thread
    val view = vlcView
    vlcView = null

    // Clear callbacks first on UI thread to stop the event stream
    view?.let { v ->
        v.onPlaybackStateChanged = null
        v.onBufferingChanged = null
        v.onTimeChanged = null
        v.onLengthChanged = null
        v.onEndReached = null
        v.onError = null
        v.onFirstFrameRendered = null
        v.onTracksChanged = null
        
        v.release() // Clear UI listeners immediately
    }

    // Perform native VLC release on background thread to prevent UI deadlocks
    scope.launch(Dispatchers.IO) {
        try {
            view?.performNativeRelease()
            
            // Release LibVLC instance when switching away from VLC to prevent memory leak
            if (currentInternalPlayerEngine != com.nuvio.tv.data.local.InternalPlayerEngine.VLC) {
                VlcInstanceProvider.cleanup()
            }
        } finally {
            isVlcReleasing.set(false)
            Log.d(PlayerRuntimeController.TAG, "[VLC] release sequence COMPLETE")
        }
    }
}

internal suspend fun PlayerRuntimeController.updateVlcAvailableTracks() {
    val view = vlcView ?: return

    try {
        // Move track parsing to IO dispatcher to prevent UI thread blocking
        val trackSnapshot = withContext(Dispatchers.IO) {
            // Get detailed tracks with full metadata (language, codec, channels, etc.)
            val detailedTracks = view.getDetailedTracks()
            
            Log.d(PlayerRuntimeController.TAG, "[VLC] Detailed tracks snapshot - audio=${detailedTracks.audioTracks.size} subtitle=${detailedTracks.subtitleTracks.size}")
            
            // Convert VlcTrack to TrackInfo with full metadata
            val audioTracks = detailedTracks.audioTracks
                .filter { it.id != -1 } // VLC uses -1 for Disable/None
                .mapIndexed { index, track ->
                    Log.d(PlayerRuntimeController.TAG, "[VLC] Audio Track $index: id=${track.id} name=${track.name} selected=${track.isSelected}")
                    // Build display name with codec and channel info (matching MPV pattern)
                    val codecSuffix = buildList {
                        track.codec?.takeIf { it.isNotBlank() }?.let { add(it) }
                        track.channelCount?.takeIf { it > 0 }?.let { add("${it}ch") }
                    }.joinToString(" ")
                    
                    val baseName = track.name
                    val displayName = if (codecSuffix.isNotEmpty()) "$baseName ($codecSuffix)" else baseName
                    
                    TrackInfo(
                        index = index,
                        name = displayName,
                        language = track.language,
                        trackId = track.id.toString(),
                        codec = track.codec,
                        channelCount = track.channelCount,
                        sampleRate = track.sampleRate,
                        isSelected = track.isSelected,
                        isForced = false
                    )
                }
            
            val subtitleTracks = detailedTracks.subtitleTracks
                .filter { it.id != -1 }
                .mapIndexed { index, track ->
                    TrackInfo(
                        index = index,
                        name = track.name,
                        language = track.language,
                        trackId = track.id.toString(),
                        codec = track.codec,
                        isSelected = track.isSelected,
                        isForced = track.isForced
                    )
                }
            
            // Find selected indices
            val selectedAudioIndex = audioTracks.indexOfFirst { it.isSelected }.takeIf { it >= 0 } ?: -1
            val selectedSubtitleIndex = subtitleTracks.indexOfFirst { it.isSelected }
            
            VlcTrackSnapshotInternal(audioTracks, subtitleTracks, selectedAudioIndex, selectedSubtitleIndex)
        }
        
        // Update UI state on main thread
        Log.d(PlayerRuntimeController.TAG, "[VLC] Final tracks - audio=${trackSnapshot.audioTracks.size} subtitle=${trackSnapshot.subtitleTracks.size} selectedAudio=${trackSnapshot.selectedAudioIndex} selectedSub=${trackSnapshot.selectedSubtitleIndex}")

        hasScannedTextTracksOnce = true

        _uiState.update { state ->
            state.copy(
                audioTracks = trackSnapshot.audioTracks,
                subtitleTracks = trackSnapshot.subtitleTracks,
                selectedAudioTrackIndex = trackSnapshot.selectedAudioIndex,
                selectedSubtitleTrackIndex = trackSnapshot.selectedSubtitleIndex
            )
        }

        updateAudioControlAvailability(trackSnapshot.audioTracks, trackSnapshot.selectedAudioIndex)
    } catch (e: Exception) {
        Log.e(PlayerRuntimeController.TAG, "[VLC] Track extraction failed", e)
    }
}

private data class VlcTrackSnapshotInternal(
    val audioTracks: List<TrackInfo>,
    val subtitleTracks: List<TrackInfo>,
    val selectedAudioIndex: Int,
    val selectedSubtitleIndex: Int
)

// VLC track selection functions
internal fun PlayerRuntimeController.selectVlcAudioTrack(index: Int) {
    val view = vlcView ?: return
    val tracks = _uiState.value.audioTracks
    if (index < 0 || index >= tracks.size) return

    val track = tracks[index]
    val trackId = track.trackId?.toIntOrNull()
    if (trackId == null) {
        Log.w(PlayerRuntimeController.TAG, "[VLC] selectVlcAudioTrack - trackId is invalid for index=$index, skipping selection")
        return
    }

    Log.d(PlayerRuntimeController.TAG, "[VLC] selectVlcAudioTrack - index=$index trackId=$trackId")
    if (view.selectAudioTrack(trackId)) {
        _uiState.update { it.copy(selectedAudioTrackIndex = index) }
    } else {
        Log.w(PlayerRuntimeController.TAG, "[VLC] selectVlcAudioTrack - failed to select trackId=$trackId")
    }
}

internal fun PlayerRuntimeController.selectVlcSubtitleTrack(index: Int) {
    val view = vlcView ?: return
    val tracks = _uiState.value.subtitleTracks

    if (index < 0) {
        // Disable subtitles
        Log.d(PlayerRuntimeController.TAG, "[VLC] selectVlcSubtitleTrack - disabling subtitles")
        view.disableSubtitles()
        _uiState.update { it.copy(selectedSubtitleTrackIndex = -1) }
        return
    }

    if (index >= tracks.size) return

    val track = tracks[index]
    val trackId = track.trackId?.toIntOrNull()
    if (trackId == null) {
        Log.w(PlayerRuntimeController.TAG, "[VLC] selectVlcSubtitleTrack - trackId is invalid for index=$index, skipping selection")
        return
    }

    Log.d(PlayerRuntimeController.TAG, "[VLC] selectVlcSubtitleTrack - index=$index trackId=$trackId")
    if (view.selectSubtitleTrack(trackId)) {
        _uiState.update { it.copy(selectedSubtitleTrackIndex = index) }
    } else {
        Log.w(PlayerRuntimeController.TAG, "[VLC] selectVlcSubtitleTrack - failed to select trackId=$trackId")
    }
}

// Add external subtitle to VLC player
internal fun PlayerRuntimeController.addVlcExternalSubtitle(url: String, select: Boolean = true): Boolean {
    val view = vlcView ?: return false
    Log.d(PlayerRuntimeController.TAG, "[VLC] addVlcExternalSubtitle - url=$url select=$select")
    return view.addExternalSubtitleUrl(url, select)
}

internal fun PlayerRuntimeController.isUsingVlcEngine(): Boolean {
    return _uiState.value.internalPlayerEngine == com.nuvio.tv.data.local.InternalPlayerEngine.VLC
}

internal fun PlayerRuntimeController.applyPendingVlcSeekIfNeeded(
    view: NuvioVlcSurfaceView,
    currentPositionMs: Long = view.getCurrentPosition().coerceAtLeast(0L),
    durationMs: Long = view.getDuration().coerceAtLeast(0L)
) {
    if (!isUsingVlcEngine()) return

    val state = _uiState.value
    val savedResume = pendingResumeProgress
    val targetPosition = state.pendingSeekPosition ?: savedResume?.position
    if (targetPosition == null) return

    val target = when {
        savedResume != null && durationMs > 0L -> {
            savedResume.resolveResumePosition(durationMs).coerceAtLeast(0L)
        }
        savedResume != null -> {
            // Use saved position directly even if duration is unknown
            savedResume.position.coerceAtLeast(0L)
        }
        else -> targetPosition.coerceAtLeast(0L)
    }

    if (target <= 1000L) {
        _uiState.update { it.copy(pendingSeekPosition = null) }
        pendingResumeProgress = null
        return
    }

    val tolerance = 2000L
    val isAlreadyAtTarget = currentPositionMs >= target ||
        kotlin.math.abs(currentPositionMs - target) <= tolerance

    if (isAlreadyAtTarget) {
        if (state.pendingSeekPosition != null || savedResume != null) {
            _uiState.update { it.copy(pendingSeekPosition = null) }
            pendingResumeProgress = null
        }
        return
    }

    val canSeekNow = durationMs > 0L || currentPositionMs > 0L || hasRenderedFirstFrame
    if (!canSeekNow) return

    // Prevent concurrent seeks using atomic flag
    if (!vlcSeekInProgress.compareAndSet(false, true)) {
        return  // Seek already in progress
    }

    Log.d(PlayerRuntimeController.TAG, "[VLC] applyPendingVlcSeekIfNeeded: initiating seek to $target")

    // Initiate seek
    view.seekTo(target)
    
    // Clear pending state AFTER seek completes to prevent race conditions
    scope.launch {
        delay(500)  // Wait for seek buffering to complete
        vlcSeekInProgress.set(false)
        _uiState.update { it.copy(pendingSeekPosition = null) }
        pendingResumeProgress = null
    }
}
